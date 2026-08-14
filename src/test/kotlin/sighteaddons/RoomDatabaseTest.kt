package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The records table groups rooms by the type behind their name. The history only stores names, so
 * this lookup is the single link between the two — if it returns null the whole table collapses
 * into one "other" group without any error to show for it.
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

    // --- the inputs ClearPoints weights rooms by (clearpoints-001) ---

    /**
     * The weighting reads three things out of this database — kind, secret count and (through the
     * map) size — and it is only worth anything if the bundled data actually distinguishes rooms by
     * them. `rooms.json` is Odin's file verbatim under BSD-3 and must never be edited to suit us, so
     * this is a check on what is there rather than a specification of what should be.
     */
    @Test
    fun `the database separates the rooms the weighting has to separate`() {
        val puzzle = RoomDatabase.infoByName("Ice Fill")
        val plain = RoomDatabase.infoByName("Admin")
        val heavy = RoomDatabase.infoByName("Mines")
        assertNotNull(puzzle)
        assertNotNull(plain)
        assertNotNull(heavy)

        // A puzzle with no secrets at all, so its bonus is the only thing separating it from a
        // plain room — and the pair proves the kind alone carries weight.
        assertEquals("PUZZLE", puzzle!!.type)
        assertEquals(0, puzzle.secrets)
        assertEquals("NORMAL", plain!!.type)
        assertEquals(0, plain.secrets)
        // And a plain room with ten of them, so the secret count alone does too.
        assertEquals("NORMAL", heavy!!.type)
        assertEquals(10, heavy.secrets)
    }

    /**
     * The feature's user-visible behaviour, against the real database rather than a fixture: after a
     * run, a puzzle and a plain 1x1 no longer carry the same points. Both rooms below are 1x1 and
     * `Ice Fill` holds no secrets, so the only difference the weighting can be reading is that one
     * of them is a puzzle.
     */
    @Test
    fun `a real puzzle outscores a real empty room`() {
        fun weigh(name: String): Double {
            val info = RoomDatabase.infoByName(name)
            assertNotNull(info, name)
            val cells = setOf(Pos(0, 0))
            return ContributionTracker.weightOf(TrackedRoom(RoomType.ROOM, cells, cells).also { it.info = info })
        }
        assertTrue(weigh("Ice Fill") > weigh("Admin"), "Ice Fill is a puzzle; Admin is an empty 1x1")
        assertTrue(weigh("Mines") > weigh("Admin"), "Mines holds ten secrets; Admin holds none")
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
