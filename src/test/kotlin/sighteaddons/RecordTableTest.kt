package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `/sa` history table has one arrangement at a time: a chip says which rooms, a column says in
 * which order. What has to hold is that the two never interfere — and that a room with no time for
 * the sorted column stays at the bottom whichever way that column points, because reversing it
 * otherwise opens the table on a screen of dashes.
 */
class RecordTableTest {
    private val types = mapOf(
        "Water Board" to "PUZZLE",
        "Trap" to "TRAP",
        "Catwalk" to "NORMAL",
        "Ice Fill" to "NORMAL",
        "Blood" to "BLOOD",
    )

    /** Water Board has both kinds, Catwalk only a clear, Ice Fill only a secret run. */
    private fun rows(): List<RecordTable.Row> {
        val records = mapOf(
            "Water Board|clear" to RoomHistory.Record(824, 7, 5_000),
            "Water Board|secretrun" to RoomHistory.Record(248, 7, 5_000),
            "Trap|clear" to RoomHistory.Record(1240, 3, 2_000),
            "Catwalk|clear" to RoomHistory.Record(368, 12, 9_000),
            "Ice Fill|secretrun" to RoomHistory.Record(500, 4, 1_000),
            "Blood|clear" to RoomHistory.Record(2080, 1, 7_000),
        )
        return RecordTable.rows(records) { types[it] }
    }

    @Test
    fun `one row per room, both kinds side by side`() {
        val rows = rows().associateBy { it.room }
        assertEquals(5, rows.size) // and not one per record key

        val water = rows.getValue("Water Board")
        assertEquals(824, water.clear)
        assertEquals(248, water.secrets)
        assertEquals(7, water.runs)
        assertEquals("puzzle", water.typeLabel)

        // Runs come from the clear when there is one: a room is completed once per run, and its
        // secret run is a second event in that same run rather than a second completion.
        assertEquals(12, rows.getValue("Catwalk").runs)
        assertEquals(4, rows.getValue("Ice Fill").runs) // no clear, so the secret run answers for it
        assertEquals(null, rows.getValue("Ice Fill").clear)
    }

    @Test
    fun `a room the database does not know still gets a row`() {
        val rows = RecordTable.rows(mapOf("Ghost|clear" to RoomHistory.Record(100, 1, 1_000))) { null }
        assertEquals(1, rows.size)
        assertEquals("?", rows.single().typeLabel)
        // And it is reachable: "other" is the leftovers, so no room can fall out of every chip.
        assertTrue(RecordTable.Filter.OTHER.matches(null))
    }

    @Test
    fun `chips partition the rooms and their counts add up to all`() {
        val counts = RecordTable.counts(rows())
        assertEquals(5, counts.getValue(RecordTable.Filter.ALL))
        assertEquals(1, counts.getValue(RecordTable.Filter.PUZZLE))
        assertEquals(1, counts.getValue(RecordTable.Filter.TRAP))
        assertEquals(0, counts.getValue(RecordTable.Filter.RARE))
        assertEquals(2, counts.getValue(RecordTable.Filter.NORMAL))
        assertEquals(1, counts.getValue(RecordTable.Filter.OTHER)) // Blood, which no chip names
        // Every room lands in exactly one chip besides "all".
        val named = RecordTable.Filter.entries.filter { it != RecordTable.Filter.ALL }.sumOf { counts.getValue(it) }
        assertEquals(counts.getValue(RecordTable.Filter.ALL), named)
    }

    @Test
    fun `search is a case-insensitive substring and leaves the counts consistent`() {
        assertEquals(listOf("Water Board"), RecordTable.search(rows(), "water").map { it.room })
        assertEquals(listOf("Water Board"), RecordTable.search(rows(), "  BOARD ").map { it.room })
        assertEquals(listOf("Catwalk", "Water Board"), RecordTable.search(rows(), "wa").map { it.room }.sorted())
        assertEquals(5, RecordTable.search(rows(), "").size)
        assertEquals(0, RecordTable.search(rows(), "zzz").size)

        // Counts follow the search, so clicking a chip yields exactly the number it advertises.
        val counts = RecordTable.counts(RecordTable.search(rows(), "wa"))
        assertEquals(2, counts.getValue(RecordTable.Filter.ALL))
        assertEquals(1, counts.getValue(RecordTable.Filter.PUZZLE))
        assertEquals(1, counts.getValue(RecordTable.Filter.NORMAL))
    }

    @Test
    fun `missing times sort last in both directions`() {
        val ascending = RecordTable.sort(rows(), RecordTable.Sort.CLEAR, desc = false).map { it.room }
        val descending = RecordTable.sort(rows(), RecordTable.Sort.CLEAR, desc = true).map { it.room }

        assertEquals(listOf("Catwalk", "Water Board", "Trap", "Blood", "Ice Fill"), ascending)
        // Reversed, except that Ice Fill — which has no clear at all — stays at the bottom instead
        // of leading the table as the slowest room.
        assertEquals(listOf("Blood", "Trap", "Water Board", "Catwalk", "Ice Fill"), descending)

        val bySecrets = RecordTable.sort(rows(), RecordTable.Sort.SECRETS, desc = true).map { it.room }
        assertEquals(listOf("Ice Fill", "Water Board"), bySecrets.take(2))
        assertEquals(listOf("Blood", "Catwalk", "Trap"), bySecrets.drop(2)) // no secret run, name order
    }

    @Test
    fun `every column sorts, and the default puts the newest room first`() {
        assertEquals(
            listOf("Catwalk", "Blood", "Water Board", "Trap", "Ice Fill"),
            RecordTable.sort(rows(), RecordTable.Sort.LAST, desc = true).map { it.room },
        )
        assertEquals(
            listOf("Blood", "Catwalk", "Ice Fill", "Trap", "Water Board"),
            RecordTable.sort(rows(), RecordTable.Sort.ROOM, desc = false).map { it.room },
        )
        assertEquals(
            listOf("Catwalk", "Water Board", "Ice Fill", "Trap", "Blood"),
            RecordTable.sort(rows(), RecordTable.Sort.RUNS, desc = true).map { it.room },
        )
        // A run count of 0 is a value, not a gap, so it sorts with the numbers rather than to the end.
        val noRuns = RecordTable.rows(mapOf("Old Room|secrets" to RoomHistory.Record(900, 2, 3_000))) { null }
        assertEquals(0, noRuns.size) // retired kind: no row at all, so no zero to sort
    }

    @Test
    fun `sorting by type groups the types without headings`() {
        val byType = RecordTable.sort(rows(), RecordTable.Sort.TYPE, desc = false)
        assertEquals(listOf("blood", "normal", "normal", "puzzle", "trap"), byType.map { it.typeLabel })
        // Inside one type the room name decides, so the order never depends on the file's order.
        assertEquals(listOf("Catwalk", "Ice Fill"), byType.filter { it.typeLabel == "normal" }.map { it.room })
    }

    /**
     * The order escape works in, which is also what the footer prints. The screen said "esc  close"
     * while a chip was active and escape reset the chip instead — the two conditions had drifted, so
     * now there is only one of them and it is here.
     */
    @Test
    fun `escape undoes the search before the chip and only then closes`() {
        assertEquals(RecordTable.Narrowing.SEARCH, RecordTable.narrowing("water", RecordTable.Filter.PUZZLE))
        assertEquals(RecordTable.Narrowing.SEARCH, RecordTable.narrowing("water", RecordTable.Filter.ALL))
        assertEquals(RecordTable.Narrowing.CHIP, RecordTable.narrowing("", RecordTable.Filter.PUZZLE))
        assertEquals(RecordTable.Narrowing.NONE, RecordTable.narrowing("", RecordTable.Filter.ALL))
    }

    @Test
    fun `filter and sort do not interfere`() {
        val normal = rows().filter { RecordTable.Filter.NORMAL.matches(it.type) }
        assertEquals(
            listOf("Catwalk", "Ice Fill"),
            RecordTable.sort(normal, RecordTable.Sort.CLEAR, desc = false).map { it.room },
        )
        // Same chip, other column: the same two rooms, only reordered — no room appears or vanishes
        // because of a sort, which is what the old grouped-by-type view did.
        assertEquals(
            setOf("Catwalk", "Ice Fill"),
            RecordTable.sort(normal, RecordTable.Sort.LAST, desc = true).map { it.room }.toSet(),
        )
    }
}
