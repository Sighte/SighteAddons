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
}
