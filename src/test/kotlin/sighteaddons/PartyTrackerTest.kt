package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
