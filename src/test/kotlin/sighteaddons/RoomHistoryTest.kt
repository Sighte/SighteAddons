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
    fun `unreadable lines are counted, not fatal`() {
        val records = RoomHistory.fold(
            sequenceOf(line("Catwalk", "clear", 120, 1_000), "", "{not json", """{"room":"X"}"""),
        )

        assertEquals(1, records.byKey.size)
        assertEquals(1, records.entries)
        assertEquals(2, records.malformed) // the blank line is skipped, not counted
    }
}
