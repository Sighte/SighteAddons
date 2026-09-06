package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sighteaddons.ui.screens.SoloRundown

/**
 * The file format and the reading of it. A record that does not survive its own round trip is a run
 * that vanishes from the tab after a relaunch, and a store one bad file can break is not a store.
 */
class SoloRunsTest {
    @Test
    fun `a record survives the round trip`() {
        val sample = SoloRuns.sample()
        val back = SoloRuns.decode(SoloRuns.encode(sample))
        assertNotNull(back)
        assertEquals(sample, back)
    }

    @Test
    fun `the fold keeps what it can read, newest first`() {
        val older = SoloRuns.sample(now = 1_000L)
        val newer = SoloRuns.sample(now = 2_000L)
        val bodies = sequenceOf(
            SoloRuns.encode(older).toString(),
            "{ not json",
            """{"v":1,"ts":5,"floor":"M7"}""",
            SoloRuns.encode(newer).toString(),
        )
        val folded = SoloRuns.fold(bodies)
        assertEquals(listOf(2_000L, 1_000L), folded.map { it.ts })
    }

    @Test
    fun `the best 300 ignores runs that never got there`() {
        val reached = SoloRuns.sample(now = 1L)
        val short = reached.copy(ts = 2L, score = reached.score.copy(marks = reached.score.marks.filter { it.threshold == 270 }))
        assertEquals(4620, SoloRuns.bestTo300(listOf(short, reached), "M7"))
        assertNull(SoloRuns.bestTo300(listOf(short), "M7"))
        assertNull(SoloRuns.bestTo300(listOf(reached), "F7"))
    }

    @Test
    fun `stops fold the doorway brush and carry the corridor`() {
        val record = SoloRuns.sample()
        val stops = SoloRundown.stops(record)
        // Atlas is two cells walked back and forth; the 20-tick return to (2,4) before the puzzle is a brush.
        val atlas = stops.first { it.room.name == "Atlas" }
        assertEquals(40, atlas.enter)
        assertEquals(540, atlas.leave, "the brush of the first cell on the way out is folded into the stay")
        assertEquals(480, atlas.clear, "the checkmark landed inside this stop")
        // Crypt is entered at 900 and its second cell left at 1400; the brush back at 1400-1430 folds in.
        val crypt = stops.first { it.room.name == "Crypt" }
        assertEquals(900 to 1430, crypt.enter to crypt.leave)
        // The way back to the blood room passes through rooms already done: those are stops too, without a clear.
        val back = stops.filter { it.enter >= 4200 }
        assertTrue(back.all { it.clear == null })
        val blood = stops.last()
        assertEquals("Blood", blood.room.name)
        assertEquals(1, stops.count { it.room.type == RoomType.ENTRANCE })
    }

    @Test
    fun `the list row names the time to 300 and the best`() {
        val rows = SoloRundown.listRows(listOf(SoloRuns.sample()), now = SoloRuns.sample().ts)
        val row = rows.single()
        assertEquals(300, row.reached)
        assertEquals(DungeonGrid.formatTicks(4620), row.time)
        assertTrue(row.pb)
        assertTrue(row.label.startsWith("M7 · "))
    }
}
