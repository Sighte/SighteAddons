package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gap-pixel offsets behind [DungeonLayout.scan], driven from a painted map.
 *
 * Every probe in that function is a pixel coordinate relative to a cell's corner, and a probe one
 * pixel off reads a room's fill as a door or a door as nothing — silently, on a real floor, where the
 * only symptom is a map with the wrong number of doors. So the map is painted here the way Hypixel
 * paints it (rooms of `rs` pixels on a `rs + 4` lattice, joins filling the gap, doors as a short bar
 * in the gap's middle, checkmarks below the centre) and the scan is read against it.
 */
class DungeonLayoutTest {
    private val rs = 16
    private val step = rs + DungeonGrid.MAP_GAP
    private val ox = 5
    private val oz = 7

    private val map = HashMap<Pair<Int, Int>, Int>()

    private fun sample(x: Int, z: Int): Int = if (x < 0 || z < 0 || x >= 128 || z >= 128) -1 else map[x to z] ?: 0

    private fun paint(gx: Int, gz: Int, colour: Int) {
        for (dx in 0 until rs) for (dz in 0 until rs) map[(ox + gx * step + dx) to (oz + gz * step + dz)] = colour
    }

    /** Fills the whole gap between two cells, which is how the map draws one room over two segments. */
    private fun join(gx: Int, gz: Int, right: Boolean, colour: Int) {
        val x0 = ox + gx * step
        val z0 = oz + gz * step
        if (right) {
            for (dx in rs until step) for (dz in 0 until rs) map[(x0 + dx) to (z0 + dz)] = colour
        } else {
            for (dx in 0 until rs) for (dz in rs until step) map[(x0 + dx) to (z0 + dz)] = colour
        }
    }

    /** A door: the middle six rows of the gap, first and last rows left empty. */
    private fun door(gx: Int, gz: Int, right: Boolean, colour: Int) {
        val x0 = ox + gx * step
        val z0 = oz + gz * step
        val half = rs / 2
        if (right) {
            for (dx in rs until step) for (dz in (half - 3) until (half + 3)) map[(x0 + dx) to (z0 + dz)] = colour
        } else {
            for (dx in (half - 3) until (half + 3)) for (dz in rs until step) map[(x0 + dx) to (z0 + dz)] = colour
        }
    }

    /** A checkmark glyph: a few pixels at and under the segment centre, as the real one sits. */
    private fun check(gx: Int, gz: Int, colour: Int) {
        val cx = ox + gx * step + rs / 2
        val cz = oz + gz * step + rs / 2
        for (dz in 0..3) map[cx to (cz + dz)] = colour
    }

    private fun floor(): Layout {
        val room = RoomType.ROOM.color.toInt()
        // Row 2: entrance at (1,2); a 2x1 room at (2,2)-(3,2), cleared; a puzzle at (4,2) with a green check.
        paint(1, 2, RoomType.ENTRANCE.color.toInt())
        paint(2, 2, room)
        paint(3, 2, room)
        join(2, 2, right = true, colour = room)
        check(2, 2, DungeonMapReader.WHITE.toInt())
        paint(4, 2, RoomType.PUZZLE.color.toInt())
        check(4, 2, DungeonMapReader.GREEN.toInt())
        door(1, 2, right = true, colour = room)
        door(3, 2, right = true, colour = RoomType.PUZZLE.color.toInt())
        // Row 1: a blood room over the entrance behind a blood door; a grey unopened cell over the puzzle
        // behind a wither door.
        paint(1, 1, RoomType.BLOOD.color.toInt())
        door(1, 1, right = false, colour = RoomType.BLOOD.color.toInt())
        paint(4, 1, DungeonLayout.UNOPENED_ALT)
        door(4, 1, right = false, colour = DungeonLayout.UNOPENED_ALT)
        return DungeonLayout.scan(::sample, Pos(ox + 1 * step, oz + 2 * step), rs)
    }

    @Test
    fun `rooms, segments and states come off the painted map`() {
        val layout = floor()
        assertEquals(5, layout.rooms.size, "entrance, 2x1 room, puzzle, blood, unopened")
        assertEquals(4, layout.cols)
        assertEquals(2, layout.rows)
        // Normalised to the rooms seen: column 1 is the first with anything on it, row 1 likewise.
        assertEquals(Cell(0, 1), layout.entrance)

        val twoByOne = layout.roomAt(Cell(1, 1))
        assertNotNull(twoByOne)
        assertEquals(setOf(Cell(1, 1), Cell(2, 1)), twoByOne!!.cells, "the filled gap joins the two segments")
        assertEquals(RoomType.ROOM, twoByOne.type)
        assertEquals(RoomState.CLEARED, twoByOne.state)

        val puzzle = layout.roomAt(Cell(3, 1))!!
        assertEquals(RoomType.PUZZLE, puzzle.type)
        assertEquals(RoomState.GREEN, puzzle.state)
        assertEquals(1, puzzle.cells.size, "a door into a puzzle does not join it to the room")

        assertEquals(RoomState.DISCOVERED, layout.roomAt(Cell(0, 1))!!.state, "the entrance's own green is not a checkmark")
        assertEquals(RoomType.BLOOD, layout.roomAt(Cell(0, 0))!!.type)
        assertEquals(RoomState.DISCOVERED, layout.roomAt(Cell(0, 0))!!.state, "the blood room's own red is not a failure")
        val grey = layout.roomAt(Cell(3, 0))!!
        assertEquals(RoomType.UNKNOWN, grey.type)
        assertEquals(RoomState.UNOPENED, grey.state)
        assertNull(layout.roomAt(Cell(2, 0)))
    }

    @Test
    fun `doors are the coloured middle of an otherwise empty gap`() {
        val layout = floor()
        val doors = layout.doors.map { Triple(it.a, it.b, it.type) }.toSet()
        assertEquals(
            setOf(
                Triple(Cell(0, 1), Cell(1, 1), DoorType.NORMAL),
                Triple(Cell(2, 1), Cell(3, 1), DoorType.NORMAL),
                Triple(Cell(0, 0), Cell(0, 1), DoorType.BLOOD),
                Triple(Cell(3, 0), Cell(3, 1), DoorType.WITHER),
            ),
            doors,
        )
        assertTrue(layout.doors.none { it.a == Cell(1, 1) && it.b == Cell(2, 1) }, "a segment join is not a door")
    }

    @Test
    fun `world and grid agree through the entrance`() {
        val layout = floor()
        val physicalEntrance = Pos(-200, -168)
        // One room east and one north of the entrance.
        val world = Pos(physicalEntrance.x + DungeonGrid.ROOM_SIZE, physicalEntrance.z - DungeonGrid.ROOM_SIZE)
        val cell = DungeonLayout.gridOf(world, physicalEntrance, layout.entrance)
        assertEquals(Cell(1, 0), cell)
        assertEquals(world, DungeonLayout.worldOf(cell, physicalEntrance, layout.entrance))
    }

    @Test
    fun `an empty map is an empty layout`() {
        val layout = DungeonLayout.scan({ _, _ -> 0 }, Pos(40, 40), rs)
        assertTrue(layout.rooms.isEmpty())
        assertEquals(0, layout.cols)
    }
}
