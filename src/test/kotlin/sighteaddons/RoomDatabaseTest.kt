package sighteaddons

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The records table groups rooms by the type behind their name. The history only stores names, so
 * this lookup is the single link between the two — if it returns null the whole table collapses
 * into one "other" group without any error to show for it.
 */
class RoomDatabaseTest {
    /**
     * The weighting cases below read the bundled `rooms.json` and must read nothing else. Pinned to
     * the seed layer for the same reason `ContributionTrackerTest` is: left alone [RoomStats]
     * resolves a file from outside this repository, and a case about the database would start
     * depending on whichever scores the machine happens to have cached.
     */
    @BeforeEach
    fun seeded() = RoomStats.use(RoomScores.NONE)

    @AfterEach
    fun release() = RoomStats.use(null)
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
     * The weighting reads two things out of this database — the room's **name**, which is both the
     * seed table's key and the key the measured averages are folded under, and its **secret count**.
     * Kind is read only to tell a puzzle from everything else. (Up to `clearpoints-001` it also read
     * size and paid a bonus per kind; `clearpoints-002` deletes both, so this file is consulted for
     * less than it used to be, not more.)
     *
     * `rooms.json` is Odin's file verbatim under BSD-3 and must never be edited to suit us, so this
     * is a check on what is there rather than a specification of what should be.
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

    private fun weigh(name: String): Double {
        val info = RoomDatabase.infoByName(name)
        assertNotNull(info, name)
        val cells = setOf(Pos(0, 0))
        return ContributionTracker.weightOf(TrackedRoom(RoomType.ROOM, cells, cells).also { it.info = info })
    }

    /**
     * The feature's user-visible behaviour, against the real database rather than a fixture: after a
     * run, a puzzle and a plain 1x1 no longer carry the same points. Both rooms below are 1x1 and
     * `Ice Fill` holds no secrets, so the only difference the weighting can be reading is the room's
     * identity.
     */
    @Test
    fun `a real puzzle outscores a real empty room`() {
        assertTrue(weigh("Ice Fill") > weigh("Admin"), "Ice Fill is a puzzle; Admin is an empty 1x1")
        assertTrue(weigh("Mines") > weigh("Admin"), "Mines holds ten secrets; Admin holds none")
    }

    /**
     * **The silent failure this feature can have, checked against the file rather than against
     * itself.** Two rooms are seeded by name, and the keys have to be the database's spelling — the
     * database writes `Ice Fill` and `Water Board`, two words each, not `IceFill` or `Waterboard`.
     * A mistyped key raises nothing: the lookup simply misses and the room quietly drops to the
     * ordinary puzzle seed, which is a plausible number and therefore an invisible defect.
     *
     * Checked through [ContributionTracker.weightOf] against the real entries, so the assertion is
     * on the seed the user gave rather than on the map this test could otherwise be reading back to
     * itself. All three are secretless in `rooms.json`, so the weight is the seed exactly.
     */
    @Test
    fun `the seeded rooms are spelled the way the database spells them`() {
        for (name in listOf("Ice Fill", "Water Board", "Quiz")) {
            assertEquals("PUZZLE", RoomDatabase.infoByName(name)?.type, "$name is not in rooms.json under that name")
            assertEquals(0, RoomDatabase.infoByName(name)?.secrets, "$name gained secrets; the numbers below move")
        }
        assertEquals(2.0, weigh("Ice Fill"), 1e-9)
        assertEquals(1.5, weigh("Water Board"), 1e-9)
        assertEquals(1.0, weigh("Quiz"), 1e-9)
        assertEquals(1.0, weigh("Boulder"), 1e-9, "a puzzle the user did not name individually")
        assertEquals(0.75, weigh("Admin"), 1e-9, "and an ordinary room")

        assertTrue(
            weigh("Ice Fill") > weigh("Water Board") && weigh("Water Board") > weigh("Boulder"),
            "the user's ordering of the puzzles is not what the model produces",
        )
    }

    /**
     * **The number that says old standings and new ones are not comparable.** `Pipes` is a 1x4
     * holding seven secrets, and it is the room the brief picked because both formulas are easy to
     * read off it:
     *
     *     clearpoints-001   1.00 + 7 x 0.25 + 3 x 0.50  =  4.25
     *     clearpoints-002   0.75 + 7 x 0.25             =  2.50
     *
     * Its three extra segments stop being paid for at all — under the new model a 1x4 earns its
     * points by measuring slow, and `Pipes` has never been measured. On the one real M7 there is,
     * the old formula scored rooms from 1.00 (`Hall`) to 4.50 (`Cathedral`) and `Pipes` at exactly
     * the 4.25 above; every room comes down under this one and they come down by different amounts,
     * so a per-player total from an older build cannot be held next to one from this build. That run
     * is committed — `docs/evidence/session-1786719912927/`, whose `readout.sh` asserts all three of
     * those numbers out of the run's own `award` events, so this KDoc cites evidence rather than
     * recollection.
     *
     * Pinned against the real database rather than left in a comment, because a reader who wants to
     * know what changed will look for a number and this is it.
     */
    @Test
    fun `the seed weight of Pipes is the user's model, not the old one`() {
        val pipes = RoomDatabase.infoByName("Pipes")
        assertNotNull(pipes)
        assertEquals(7, pipes!!.secrets, "Pipes' seven secrets are half of the arithmetic below")
        assertEquals("1x4", pipes.shape, "and its four segments are what stopped being paid for")

        assertEquals(2.50, weigh("Pipes"), 1e-9, "the old formula made this 4.25")
        assertEquals(0.75 + 7 * 0.25, weigh("Pipes"), 1e-9)
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
