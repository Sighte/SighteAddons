package sighteaddons

import net.minecraft.world.level.saveddata.maps.MapItemSavedData

/** A position on the dungeon's room lattice: column and row, both from zero at the floor's north-west. */
data class Cell(val gx: Int, val gz: Int)

/**
 * What the item map says about a room, as far as the map can say it.
 *
 * There is no `UNDISCOVERED`: a room the map has not revealed is not on the map, so it is not in a
 * [Layout] either. What the map *does* show before anybody walked in is the grey placeholder Hypixel
 * draws behind an opened door, and that is [UNOPENED].
 */
enum class RoomState { UNOPENED, DISCOVERED, CLEARED, GREEN, FAILED }

enum class DoorType { NORMAL, WITHER, BLOOD }

class LayoutRoom(val type: RoomType, val cells: Set<Cell>, val state: RoomState)

data class Door(val a: Cell, val b: Cell, val type: DoorType)

/**
 * The whole floor as the item map shows it: every revealed room with its cells and state, and the
 * doors between them. [cols] × [rows] is the bounding box of the cells, [entrance] is where the
 * green room sits in it.
 */
class Layout(
    val cols: Int,
    val rows: Int,
    val entrance: Cell,
    val rooms: List<LayoutRoom>,
    val doors: List<Door>,
) {
    private val byCell: Map<Cell, LayoutRoom> = HashMap<Cell, LayoutRoom>().also { map ->
        for (room in rooms) for (cell in room.cells) map[cell] = room
    }

    fun roomAt(cell: Cell): LayoutRoom? = byCell[cell]
}

/**
 * Reads the *whole* item map into a [Layout], as opposed to [ContributionTracker], which registers a
 * room the first time a party member is seen standing in it and nothing else.
 *
 * A second, purely reading look at the same 128×128 bytes — it shares no state with the tracker and
 * changes nothing about how rooms are discovered for attribution. It exists for the solo-clear record,
 * which wants to draw the floor afterwards: the grey rooms nobody opened, the doors, the shape of the
 * route. None of that has a use in the tracker, and adding it there would put a second job into the
 * function whose ordering `CLAUDE.md` protects.
 *
 * ### The lattice
 *
 * Rooms sit on a grid of `roomSize + 4` pixels ([DungeonGrid.MAP_GAP]). The entrance's top-left pixel
 * is on that lattice, so `mapEntrance % step` is the lattice's offset and every lattice position on
 * the map is `offset + k * step`. Cells are then counted from the bounding box of what was found, so a
 * cell coordinate here says where a room is relative to the other rooms — it says nothing about map
 * pixels, and [gridOf] is how a world position gets onto the same grid.
 *
 * ### What is read where
 *
 * The room's *type* is its top-left pixel, the same pixel `ContributionTracker.discover` reads. The
 * *state* is the checkmark probe [DungeonMapReader.checkmarkColor] makes, from the centre downwards
 * and ignoring the room's own fill colour — the entrance is the colour of the green check and the
 * blood room the colour of the red one. Two rooms are *one* room when the gap pixel on their shared
 * edge's first row carries the room colour, which is what [DungeonMapReader.roomSegments] has trusted
 * in production since the first release, and only plain rooms are allowed to join: every other type
 * is 1×1, as `discover` asserts.
 *
 * A *door* is a coloured pixel in the middle of a gap whose first-row pixel is empty. The middle-row
 * probe is BlackAddons' (`DungeonMap.scanDoors`), the first-row guard is what keeps a two-segment
 * room from reading as a room with a door through it. **The height of Hypixel's door sprite is not
 * measured in this repository** — the `solo_layout` event carries the door count of every run so the
 * first real floor says whether the probe lands.
 */
object DungeonLayout {

    /** The second "opened but not entered" colour, beside [RoomType.UNKNOWN]'s 85. Not in the enum: it is a state, not a type. */
    const val UNOPENED_ALT = 119

    /** Pure over a colour sampler, so a test can paint a map. [sample] returns -1 off the map and 0 for nothing drawn. */
    fun scan(sample: (Int, Int) -> Int, mapEntrance: Pos, roomSize: Int): Layout {
        val step = roomSize + DungeonGrid.MAP_GAP
        val half = roomSize / 2
        val originX = Math.floorMod(mapEntrance.x, step)
        val originZ = Math.floorMod(mapEntrance.z, step)
        val countX = (128 - originX + step - 1) / step
        val countZ = (128 - originZ + step - 1) / step

        fun x0(gx: Int) = originX + gx * step
        fun z0(gz: Int) = originZ + gz * step

        // Every lattice cell with something drawn at its top-left pixel, with the colour found there.
        val colour = HashMap<Cell, Int>()
        for (gx in 0 until countX) for (gz in 0 until countZ) {
            val c = sample(x0(gx), z0(gz))
            if (c > 0) colour[Cell(gx, gz)] = c
        }
        if (colour.isEmpty()) {
            return Layout(0, 0, Cell(0, 0), emptyList(), emptyList())
        }

        // Union-find over the first-row gap pixel, plain rooms only.
        val parent = HashMap<Cell, Cell>()
        fun root(c: Cell): Cell {
            var cur = c
            while (true) {
                val p = parent[cur] ?: return cur
                if (p == cur) return cur
                cur = p
            }
        }
        fun union(a: Cell, b: Cell) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[ra] = rb
        }
        val room = RoomType.ROOM.color.toInt()
        for ((cell, c) in colour) {
            if (c != room) continue
            val right = Cell(cell.gx + 1, cell.gz)
            if (colour[right] == room && sample(x0(cell.gx) + roomSize, z0(cell.gz)) == room) union(cell, right)
            val down = Cell(cell.gx, cell.gz + 1)
            if (colour[down] == room && sample(x0(cell.gx), z0(cell.gz) + roomSize) == room) union(cell, down)
        }

        val groups = LinkedHashMap<Cell, MutableSet<Cell>>()
        for (cell in colour.keys.sortedWith(compareBy({ it.gz }, { it.gx }))) {
            groups.getOrPut(root(cell)) { LinkedHashSet() }.add(cell)
        }

        val minX = colour.keys.minOf { it.gx }
        val minZ = colour.keys.minOf { it.gz }
        fun shifted(cell: Cell) = Cell(cell.gx - minX, cell.gz - minZ)

        val rooms = ArrayList<LayoutRoom>()
        for (cells in groups.values) {
            val first = cells.first()
            val c = colour.getValue(first)
            val type = if (c == UNOPENED_ALT) RoomType.UNKNOWN else RoomType.fromColor(c.toByte()) ?: continue
            val state = if (type == RoomType.UNKNOWN) {
                RoomState.UNOPENED
            } else {
                var found = RoomState.DISCOVERED
                probe@ for (cell in cells) {
                    for (offset in 0 until half) {
                        val p = sample(x0(cell.gx) + half, z0(cell.gz) + half + offset)
                        if (p == c || p <= 0) continue
                        val hit = when (p.toByte()) {
                            DungeonMapReader.WHITE -> RoomState.CLEARED
                            DungeonMapReader.GREEN -> RoomState.GREEN
                            DungeonMapReader.RED -> RoomState.FAILED
                            else -> null
                        }
                        if (hit != null) {
                            found = hit
                            break@probe
                        }
                    }
                }
                found
            }
            rooms.add(LayoutRoom(type, cells.mapTo(LinkedHashSet()) { shifted(it) }, state))
        }

        // Doors: a coloured middle-row gap pixel behind an empty first-row one, next to at least one room.
        val doors = ArrayList<Door>()
        for (gx in 0 until countX) for (gz in 0 until countZ) {
            val here = Cell(gx, gz)
            val right = Cell(gx + 1, gz)
            if (gx + 1 < countX && (here in colour || right in colour)) {
                if (sample(x0(gx) + roomSize, z0(gz)) <= 0) {
                    doorType(sample(x0(gx) + roomSize + 1, z0(gz) + half))?.let {
                        doors.add(Door(shifted(here), shifted(right), it))
                    }
                }
            }
            val down = Cell(gx, gz + 1)
            if (gz + 1 < countZ && (here in colour || down in colour)) {
                if (sample(x0(gx), z0(gz) + roomSize) <= 0) {
                    doorType(sample(x0(gx) + half, z0(gz) + roomSize + 1))?.let {
                        doors.add(Door(shifted(here), shifted(down), it))
                    }
                }
            }
        }

        val entrance = shifted(
            Cell(Math.floorDiv(mapEntrance.x - originX, step), Math.floorDiv(mapEntrance.z - originZ, step)),
        )
        return Layout(
            cols = colour.keys.maxOf { it.gx } - minX + 1,
            rows = colour.keys.maxOf { it.gz } - minZ + 1,
            entrance = entrance,
            rooms = rooms,
            doors = doors,
        )
    }

    private fun doorType(colour: Int): DoorType? = when {
        colour <= 0 -> null
        colour == UNOPENED_ALT -> DoorType.WITHER
        colour == RoomType.BLOOD.color.toInt() -> DoorType.BLOOD
        else -> DoorType.NORMAL
    }

    /**
     * The lattice cell of a world position's room, given where the entrance is on both.
     *
     * [cell] is a room's north-west corner from [DungeonGrid.physicalRoomPos]; the two anchors sit on the
     * same 32-block lattice, so the division is exact.
     */
    fun gridOf(cell: Pos, physicalEntrance: Pos, entrance: Cell): Cell = Cell(
        entrance.gx + Math.floorDiv(cell.x - physicalEntrance.x, DungeonGrid.ROOM_SIZE),
        entrance.gz + Math.floorDiv(cell.z - physicalEntrance.z, DungeonGrid.ROOM_SIZE),
    )

    /** Inverse of [gridOf]: the world north-west corner of a lattice cell. */
    fun worldOf(cell: Cell, physicalEntrance: Pos, entrance: Cell): Pos = Pos(
        physicalEntrance.x + (cell.gx - entrance.gx) * DungeonGrid.ROOM_SIZE,
        physicalEntrance.z + (cell.gz - entrance.gz) * DungeonGrid.ROOM_SIZE,
    )

    /** The one Minecraft-facing line: the live map as a sampler. */
    fun scan(map: MapItemSavedData, mapEntrance: Pos, roomSize: Int): Layout =
        scan({ x, z -> DungeonMapReader.colorAt(map, x, z).toInt() }, mapEntrance, roomSize)
}
