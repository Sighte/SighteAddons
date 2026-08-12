package sighteaddons

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.saveddata.maps.MapItemSavedData

/** Room types as they appear on the dungeon item map, identified by their map colour. */
enum class RoomType(val color: Byte) {
    ENTRANCE(30),
    ROOM(63),
    PUZZLE(66),
    TRAP(62),
    MINIBOSS(74),
    FAIRY(82),
    BLOOD(18),
    UNKNOWN(85),
    ;

    companion object {
        fun fromColor(color: Byte) = entries.firstOrNull { it.color == color }
    }
}

/**
 * Reads the dungeon map from the item in hotbar slot 9. This is the only source that sees
 * *all* party members and *all* room clear states, including chunks that are not loaded
 * on this client.
 */
object DungeonMapReader {
    const val RED: Byte = 18 // failed
    const val WHITE: Byte = 34 // cleared
    const val GREEN: Byte = 30 // cleared, all secrets found

    fun isCheckmark(color: Byte) = color == WHITE || color == GREEN || color == RED

    fun mapState(client: Minecraft): MapItemSavedData? {
        val level = client.level ?: return null
        val stack = client.player?.inventory?.nonEquipmentItems?.get(8) ?: return null
        val mapId = stack.get(DataComponents.MAP_ID) ?: return null
        return MapItem.getSavedData(mapId, level)
    }

    fun colorAt(map: MapItemSavedData, x: Int, z: Int): Byte =
        if (x < 0 || z < 0 || x >= 128 || z >= 128) -1 else map.colors[x + (z shl 7)]

    fun colorAt(map: MapItemSavedData, pos: Pos): Byte = colorAt(map, pos.x, pos.z)

    /**
     * Locates the entrance room to calibrate the map: its top-left pixel and the room size.
     *
     * The entrance is the only large solid green area — but a *green checkmark* on a fully looted
     * room is the same colour, so width alone is not enough to tell them apart. The entrance is
     * square, a checkmark glyph is not, so the block must extend as far down as it does across.
     */
    fun calibrate(map: MapItemSavedData): Pair<Pos, Int>? {
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                if (colorAt(map, x, y) != GREEN) continue
                if (colorAt(map, x - 1, y) == GREEN || colorAt(map, x, y - 1) == GREEN) continue
                var run = 0
                while (colorAt(map, x + run, y) == GREEN) run++
                if (run <= 5) continue
                if (colorAt(map, x, y + run - 1) != GREEN) continue // not square: a checkmark
                return Pos(x, y) to run
            }
        }
        return null
    }

    /**
     * All segments of the room whose top-left pixel is [mapPos]. Rooms bigger than 1x1 fill
     * the 4 pixel gap towards their own segments, so a coloured gap pixel means "same room".
     */
    fun roomSegments(map: MapItemSavedData, mapPos: Pos, mapRoomSize: Int, color: Byte): Set<Pos> {
        val step = mapRoomSize + DungeonGrid.MAP_GAP
        val found = mutableSetOf(mapPos)
        val queue = ArrayDeque(listOf(mapPos))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            // Gap pixel to probe -> top-left of the neighbour segment behind it.
            val neighbours = listOf(
                Pos(cur.x - 1, cur.z) to Pos(cur.x - step, cur.z),
                Pos(cur.x, cur.z - 1) to Pos(cur.x, cur.z - step),
                Pos(cur.x + mapRoomSize, cur.z) to Pos(cur.x + step, cur.z),
                Pos(cur.x, cur.z + mapRoomSize) to Pos(cur.x, cur.z + step),
            )
            for ((gap, neighbour) in neighbours) {
                if (colorAt(map, gap) == color && found.add(neighbour)) queue.add(neighbour)
            }
        }
        return found
    }

    /**
     * The room's checkmark colour, or -1 if it has none yet. The checkmark sits slightly below
     * the centre of one of the room's segments, and we do not know which one, so check all.
     *
     * A pixel matching the room's own [roomColor] is the room fill, not a checkmark — the entrance
     * is green and the blood room is red, the same colours as the green/red checkmark. Without this
     * check both would read as permanently cleared/failed; with it, their white or green *clear*
     * checkmark is still detected, so the blood room counts as a normal contribution target.
     */
    fun checkmarkColor(map: MapItemSavedData, segmentsOnMap: Collection<Pos>, mapRoomSize: Int, roomColor: Byte): Byte {
        val half = mapRoomSize / 2
        for (segment in segmentsOnMap) {
            for (offset in 0 until half) {
                val color = colorAt(map, segment.x + half, segment.z + half + offset)
                if (color != roomColor && isCheckmark(color)) return color
            }
        }
        return -1
    }
}
