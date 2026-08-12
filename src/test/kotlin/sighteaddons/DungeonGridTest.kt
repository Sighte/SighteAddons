package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DungeonGridTest {
    private val physicalEntrance = Pos(-8, -8)
    private val mapEntrance = Pos(10, 20)
    private val roomSize = 16

    @Test
    fun `room corners snap to the offset 32 grid`() {
        assertEquals(Pos(-8, -8), DungeonGrid.physicalRoomPos(0.0, 0.0))
        assertEquals(Pos(-8, -8), DungeonGrid.physicalRoomPos(23.4, 10.0))
        assertEquals(Pos(24, -8), DungeonGrid.physicalRoomPos(24.0, 10.0))
        // Negative coordinates are where a floor/truncate mixup would show up.
        assertEquals(Pos(-40, -40), DungeonGrid.physicalRoomPos(-20.0, -20.0))
        assertEquals(Pos(-72, -8), DungeonGrid.physicalRoomPos(-50.4, 0.0))
    }

    @Test
    fun `map and world coordinates round trip`() {
        val room = Pos(24, -8)
        val onMap = DungeonGrid.physicalToMap(mapEntrance, roomSize, physicalEntrance, room)
        assertEquals(Pos(30, 20), onMap)
        assertEquals(room, DungeonGrid.mapToPhysicalRoom(mapEntrance, roomSize, physicalEntrance, onMap))

        val (x, z) = DungeonGrid.mapToPhysical(mapEntrance, roomSize, physicalEntrance, 30.0, 20.0)
        assertEquals(24.0, x)
        assertEquals(-8.0, z)
    }

    @Test
    fun `ticks format as minutes seconds tenths`() {
        assertEquals("0:00.0", DungeonGrid.formatTicks(0))
        assertEquals("0:01.2", DungeonGrid.formatTicks(25)) // 1.25 s
        assertEquals("1:00.0", DungeonGrid.formatTicks(20 * 60))
        assertEquals("3:12.5", DungeonGrid.formatTicks(20 * 192 + 10))
    }

    @Test
    fun `points split by time and ignore pass through`() {
        val split = DungeonGrid.splitPoints(mapOf("A" to 300, "B" to 100, "C" to 5), 1.0, 20)
        assertEquals(setOf("A", "B"), split.keys)
        assertEquals(0.75, split["A"]!!, 1e-9)
        assertEquals(0.25, split["B"]!!, 1e-9)

        // Nobody stayed long enough: no points handed out at all.
        assertEquals(emptyMap<String, Double>(), DungeonGrid.splitPoints(mapOf("A" to 5), 1.0, 20))
    }
}
