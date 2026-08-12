package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one thing standing between a party member and a log file on someone else's server, so it is
 * worth pinning properly: stable within a session, different across sessions, and no name surviving
 * a tab row in either the parsing or the failing case.
 */
class PseudonymTest {
    private val salt = "11111111-2222-3333-4444-555555555555"
    private val other = "99999999-8888-7777-6666-555555555555"

    @Test
    fun `same name and salt is the same pseudonym, so events stay linkable`() {
        assertEquals(Pseudonym.of("bush_on_hide", salt), Pseudonym.of("bush_on_hide", salt))
        assertNotEquals(Pseudonym.of("bush_on_hide", salt), Pseudonym.of("Nordwand", salt))
    }

    /** A per-launch salt is what stops the inbox accumulating a history of a stranger. */
    @Test
    fun `a new salt gives a new pseudonym for the same name`() {
        assertNotEquals(Pseudonym.of("bush_on_hide", salt), Pseudonym.of("bush_on_hide", other))
    }

    @Test
    fun `the name does not survive the pseudonym`() {
        val alias = Pseudonym.of("bush_on_hide", salt)
        assertFalse(alias.contains("bush", ignoreCase = true))
        assertTrue(alias.matches(Regex("p-[0-9a-f]{8}")), alias)
    }

    /** The common case: only the name changes, the format stays byte for byte inspectable. */
    @Test
    fun `a parsing tab row keeps everything but the name`() {
        val row = Pseudonym.row("[42] [MVP+] bush_on_hide (Berserk VII)")!!
        assertFalse(row.contains("bush_on_hide"))
        assertTrue(row.startsWith("[42] [MVP+] "), row)
        assertTrue(row.endsWith(" (Berserk VII)"), row)
    }

    /**
     * The row that does not parse is the row the event exists for, and there is no name group to
     * replace — so the vocabulary survives and everything else name-shaped does not.
     */
    @Test
    fun `an unparsable tab row loses the name and keeps the format`() {
        val row = Pseudonym.row("[42] <<< bush_on_hide (Berserk VII) >>>")!!
        assertFalse(row.contains("bush_on_hide"))
        assertTrue(row.contains("Berserk"), row)
        assertTrue(row.contains("VII"), row)
        assertTrue(row.contains("[42]") && row.contains("<<<"), row)
    }

    @Test
    fun `a dead row keeps the marker that makes it a death`() {
        val row = Pseudonym.row("[42] bush_on_hide (DEAD)")!!
        assertFalse(row.contains("bush_on_hide"))
        assertTrue(row.contains("DEAD"), row)
    }

    @Test
    fun `an absent row stays absent`() {
        assertNull(Pseudonym.row(null))
    }

    /** `toString()` on a map keyed by player name leaks the whole roster in one field. */
    @Test
    fun `map keys are replaced and values untouched`() {
        val replaced = Pseudonym.keys(mapOf("bush_on_hide" to 812, "Nordwand" to 44))
        assertFalse(replaced.toString().contains("bush_on_hide"))
        assertFalse(replaced.toString().contains("Nordwand"))
        assertEquals(listOf(812, 44), replaced.values.toList())
        assertEquals(2, replaced.size)
    }
}
