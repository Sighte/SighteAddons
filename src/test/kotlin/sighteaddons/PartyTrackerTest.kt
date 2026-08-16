package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * If this regex stops matching, the roster is empty and the mod tracks nothing at all — the most
 * damaging failure mode there is, and entirely silent. Hence the realistic sample rows.
 */
class PartyTrackerTest {
    private fun parse(row: String): Pair<String, String>? =
        PartyTracker.TAB.matchEntire(row)?.let { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `parses rank prefixes including plus signs`() {
        assertEquals("Notch" to "Berserk", parse("[102] Notch (Berserk V)"))
        // The reason this test exists: [A-Za-z]+ excludes '+' and drops every MVP+ player.
        assertEquals("Notch" to "Berserk", parse("[102] [MVP+] Notch (Berserk V)"))
        assertEquals("Notch" to "Mage", parse("[102] [MVP++] Notch (Mage L)"))
        assertEquals("Some_One1" to "Healer", parse("[300] [VIP+] Some_One1 (Healer XXV)"))
    }

    @Test
    fun `parses emblems and dead players`() {
        assertEquals("Notch" to "Tank", parse("[102] [MVP+] Notch ✪✪✪ (Tank XX)"))
        assertEquals("Notch" to "DEAD", parse("[102] Notch (DEAD)"))
        assertEquals("Notch" to "EMPTY", parse("[102] Notch (EMPTY)"))
    }

    @Test
    fun `rejects rows that are not party members`() {
        assertNull(parse("Team Deaths: 0"))
        assertNull(parse(" Secrets Found: 12"))
        assertNull(parse("Puzzles: (3)"))
    }

    @Test
    fun `dead and empty classes count as not alive`() {
        assertEquals(false, DungeonPlayer("Notch", "DEAD").alive)
        assertEquals(false, DungeonPlayer("Notch", "EMPTY").alive)
        assertEquals(true, DungeonPlayer("Notch", "Berserk").alive)
    }

    /**
     * A real M5 log ended with `"classes":[..., "DEAD", ...]` because one player died at tick 1440
     * and never revived — their class and level were gone from the permanent record.
     */
    @Test
    fun `a death keeps the class the player had while alive`() {
        val alive = DungeonPlayer("Notch", "Mage", "XXXV")
        val dead = PartyTracker.carryLiving(alive, DungeonPlayer("Notch", "DEAD"))
        assertEquals("DEAD", dead.dungeonClass)
        assertEquals("Mage", dead.livingClass)
        assertEquals("XXXV", dead.livingLevel)
    }

    @Test
    fun `a living row carries its own class`() {
        val revived = PartyTracker.carryLiving(
            DungeonPlayer("Notch", "DEAD", "", livingClass = "Mage", livingLevel = "XXXV"),
            DungeonPlayer("Notch", "Mage", "XXXV"),
        )
        assertEquals("Mage", revived.livingClass)
        assertEquals("XXXV", revived.livingLevel)
    }

    @Test
    fun `a different player in the slot does not inherit a class`() {
        val replaced = PartyTracker.carryLiving(
            DungeonPlayer("Notch", "Mage", "XXXV"),
            DungeonPlayer("Someone_Else", "DEAD"),
        )
        assertEquals("DEAD", replaced.livingClass)
        assertEquals("", replaced.livingLevel)
    }

    // ---------------------------------------------------------------------------------------------
    // PartyTracker.assign — the decoration→player heuristic.
    //
    // Before party-001 nothing here was covered: the whole assignment lived inside `positions()`,
    // which takes a MapItemSavedData and reads DungeonSession statics, so it could not be reached
    // from a unit test at all. The cases below are the reason the extraction was worth doing —
    // `ContributionTracker.tick` only ever creates a room that some decoration resolved into, so
    // these assertions are about which rooms exist as much as about who gets credited.
    // ---------------------------------------------------------------------------------------------

    private val physicalEntrance = Pos(-8, -8)
    private val mapEntrance = Pos(10, 20)
    private val roomSize = 16

    private fun alive(name: String) = DungeonPlayer(name, "Mage")

    /** The two lines `positions()` runs on every decoration it assigns, and nothing else. */
    private fun cell(decoX: Int, decoY: Int): Pos {
        val (x, z) = DungeonGrid.mapToPhysical(
            mapEntrance, roomSize, physicalEntrance, decoX / 2.0 + 64, decoY / 2.0 + 64,
        )
        return DungeonGrid.physicalRoomPos(x, z)
    }

    @Test
    fun `the frame takes the local slot and the rest follow map order`() {
        val roster = listOf(alive("Me"), alive("B"), alive("C"), null, null)
        val result = PartyTracker.assign(roster, localSlot = 0, isFrame = listOf(true, false, false))
        assertEquals(listOf(0, 1, 2), result.slots)
        assertTrue(result.trustOrder)
        assertEquals(2, result.markers)
        assertEquals(2, result.aliveTeammates)
    }

    /**
     * The local player is looked up by name rather than assumed to be slot 0, and their marker is
     * identified by decoration *type*. Both have to hold at once, or the one marker that is never
     * ambiguous gets handed to a teammate and shifts every other assignment with it.
     */
    @Test
    fun `the frame is found by type wherever it sits, and the local slot is not assumed to be zero`() {
        val roster = listOf(alive("B"), alive("C"), alive("Me"), null, null)
        val result = PartyTracker.assign(roster, localSlot = 2, isFrame = listOf(false, true, false))
        assertEquals(listOf(0, 2, 1), result.slots)
    }

    /**
     * The damaging failure, and the only one worth guarding. The map drops a dead player's marker
     * 10-20 ticks before their tab row flips to `DEAD`, so for about a second there is one marker
     * too few. Unguarded, every teammate after the missing one slides onto somebody else's marker —
     * a *different room*, so a different cell, so a room credited to a player who was never in it
     * and, through `ContributionTracker.tick`, possibly a room that gets discovered on the strength
     * of it. The guard refuses to place teammates at all for those ticks.
     */
    @Test
    fun `one missing marker blacks out every teammate and keeps only the frame`() {
        val roster = listOf(alive("Me"), alive("B"), alive("C"), null, null)
        val result = PartyTracker.assign(roster, localSlot = 0, isFrame = listOf(true, false))
        assertEquals(listOf(0, null), result.slots)
        assertFalse(result.trustOrder)
        assertEquals(1, result.markers)
        assertEquals(2, result.aliveTeammates)

        // What the blackout is worth: the marker that survived is in a different room from the one
        // the shifted mapping would have credited, so the wrong answer would have been wrong about
        // the room and not merely about the name.
        assertNotEquals(cell(-68, -88), cell(-28, -88))
    }

    /** The counts can disagree in the other direction too — a marker the tab list has not seen yet. */
    @Test
    fun `an extra marker blacks out the teammates as well`() {
        val roster = listOf(alive("Me"), alive("B"), alive("C"), null, null)
        val result = PartyTracker.assign(roster, localSlot = 0, isFrame = listOf(true, false, false, false))
        assertEquals(listOf(0, null, null, null), result.slots)
        assertFalse(result.trustOrder)
    }

    @Test
    fun `dead and empty slots are neither counted nor assigned`() {
        val roster = listOf(alive("Me"), alive("B"), DungeonPlayer("C", "DEAD"), null, DungeonPlayer("E", "EMPTY"))
        val result = PartyTracker.assign(roster, localSlot = 0, isFrame = listOf(true, false))
        assertEquals(listOf(0, 1), result.slots)
        assertEquals(1, result.aliveTeammates)
    }

    /**
     * The local player's marker is the one identified by type rather than by counting, so their
     * death removes their own position and leaves everybody else's intact — the opposite of what a
     * teammate's death does.
     */
    @Test
    fun `the local player being dead drops only the frame`() {
        val roster = listOf(DungeonPlayer("Me", "DEAD"), alive("B"), alive("C"), null, null)
        val result = PartyTracker.assign(roster, localSlot = 0, isFrame = listOf(true, false, false))
        assertEquals(listOf(null, 1, 2), result.slots)
        assertTrue(result.trustOrder)
    }

    /**
     * The harmless failure, asserted so it stays classified as harmless. Two teammates in one room
     * produce two decorations a few pixels apart; if the order mapping swaps them, each is credited
     * with the other's decoration — and both decorations resolve to the same cell, so the set of
     * (player, room) pairs is byte-for-byte the same. `party-001` used to describe *this* as the
     * defect the feature existed to fix; it does not, and the entry was corrected.
     */
    @Test
    fun `two teammates in one room resolve to one cell whichever way round they are`() {
        val bPixel = cell(-68, -88)
        val cPixel = cell(-60, -80)
        assertEquals(bPixel, cPixel)
        assertEquals(Pos(24, -8), bPixel)
        // ...and a third teammate one room over is a genuinely different cell, so the equality above
        // is a property of sharing a room rather than of the numbers being too coarse to tell rooms
        // apart at all.
        assertNotEquals(bPixel, cell(-28, -88))
    }
}
