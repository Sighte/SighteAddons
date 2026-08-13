package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The records table in `/sa` is derived from the history file, never stored — so the fold from lines
 * to records is what has to be right.
 */
class RoomHistoryTest {
    private fun line(room: String, kind: String, ticks: Int, ts: Long) =
        """{"ts":$ts,"floor":"M5","room":"$room","kind":"$kind","ticks":$ticks,"seconds":${ticks / 20.0}}"""

    @Test
    fun `record is the minimum, with run count and latest timestamp`() {
        val records = RoomHistory.fold(
            sequenceOf(
                line("Catwalk", "clear", 200, 1_000),
                line("Catwalk", "clear", 120, 2_000),
                line("Catwalk", "clear", 160, 3_000),
                line("Catwalk", "secrets", 900, 3_000),
                line("Water Board", "clear", 824, 4_000),
            ),
        )

        val catwalk = records.byKey.getValue("Catwalk|clear")
        assertEquals(120, catwalk.ticks) // best, not last and not first
        assertEquals(3, catwalk.runs)
        assertEquals(3_000L, catwalk.lastTs) // latest, so "last" cannot show an older run
        // Clear and secrets stay separate keys — a room can be cleared without its secrets.
        assertEquals(900, records.byKey.getValue("Catwalk|secrets").ticks)
        assertEquals(1, records.byKey.getValue("Water Board|clear").runs)
        // Every valid line raises exactly one run count, which is the line total the screen shows.
        assertEquals(5, records.entries)
    }

    @Test
    fun `a teammate's secrets read as a dash, never as zero`() {
        assertEquals("(7 rooms · 12 secrets)", RoomHistory.breakdown(7, 12))
        // Zero is a claim about a teammate this client cannot make: it never saw the rooms they
        // were in alone, so an unknown count must not read like an empty one.
        assertEquals("(9 rooms · – secrets)", RoomHistory.breakdown(9, null))
        assertEquals("(0 rooms · 0 secrets)", RoomHistory.breakdown(0, 0))
    }

    @Test
    fun `a retired kind is kept in the file but gets no row in the table`() {
        val records = RoomHistory.fold(
            sequenceOf(
                line("Catwalk", RoomHistory.CLEAR, 120, 1_000),
                line("Water Board", RoomHistory.SECRETS, 300, 2_000),
                // The pre-0.5 kind. Its ticks measured something else entirely, so nothing reads it
                // any more — but the line stays, and a room that has only this must not become a row
                // of dashes with a run count of 0.
                line("Old Room", "secrets", 900, 3_000),
            ),
        )

        assertEquals(3, records.entries) // every line still counted as history
        assertEquals(
            listOf("Catwalk", "Water Board"),
            RoomHistory.roomsWithRecords(records.byKey.keys).sorted(),
        )
    }

    @Test
    fun `unreadable lines are counted, not fatal`() {
        val records = RoomHistory.fold(
            sequenceOf(line("Catwalk", "clear", 120, 1_000), "", "{not json", """{"room":"X"}"""),
        )

        assertEquals(1, records.byKey.size)
        assertEquals(1, records.entries)
        assertEquals(2, records.malformed) // the blank line is skipped, not counted
    }
}
