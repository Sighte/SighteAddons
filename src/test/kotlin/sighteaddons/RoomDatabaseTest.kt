package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The records table groups rooms by the type behind their name. The history only stores names, so
 * this lookup is the single link between the two — if it returns null the whole table collapses
 * into one "sonstige" group without any error to show for it.
 */
class RoomDatabaseTest {
    @Test
    fun `resolves a room type from its name`() {
        assertEquals("PUZZLE", RoomDatabase.infoByName("Ice Fill")?.type)
        assertEquals("TRAP", RoomDatabase.infoByName("New Trap")?.type)
        assertEquals("RARE", RoomDatabase.infoByName("Trinity")?.type)
        assertEquals("NORMAL", RoomDatabase.infoByName("Mossy")?.type)
    }

    @Test
    fun `carries the numbers the table and the report read`() {
        val room = RoomDatabase.infoByName("Ice Fill")
        assertNotNull(room)
        assertEquals("Ice Fill", room!!.name)
        assertTrue(room.shape.isNotEmpty())
    }

    @Test
    fun `an unknown name resolves to nothing rather than a wrong room`() {
        assertNull(RoomDatabase.infoByName("Not A Room"))
        assertNull(RoomDatabase.infoByName(""))
    }

    @Test
    fun `every group the records table draws is actually populated`() {
        // If a type ever disappears from rooms.json, its heading would render over an empty list.
        for (type in listOf("PUZZLE", "TRAP", "RARE", "NORMAL")) {
            assertTrue(
                listOf("Ice Fill", "New Trap", "Trinity", "Mossy")
                    .any { RoomDatabase.infoByName(it)?.type == type },
                "no room of type $type",
            )
        }
    }
}
