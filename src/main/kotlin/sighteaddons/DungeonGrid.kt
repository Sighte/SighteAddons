package sighteaddons

/** A position on the 32-block dungeon grid, or a pixel on the 128x128 item map. */
data class Pos(val x: Int, val z: Int)

/**
 * Pure grid math — no Minecraft types, so it is unit testable.
 *
 * Hypixel dungeons are aligned to a 32x32 block grid that Hypixel offset by 8 blocks
 * in Skyblock 0.12.3. On the item map a room is [mapRoomSize] pixels wide with a 4 pixel
 * gap between rooms, so one grid step is `mapRoomSize + 4` pixels.
 */
object DungeonGrid {
    const val ROOM_SIZE = 32
    const val MAP_GAP = 4

    /**
     * Northwest corner of the room containing the given world coordinate.
     *
     * The +0.5 splits room borders evenly between neighbours, the +8/-8 compensates
     * Hypixel's dungeon offset. Truncating (not flooring) matches how the reference
     * implementations do it, which keeps the half-block border on the correct side.
     */
    fun physicalRoomPos(x: Double, z: Double): Pos {
        val px = (x + 8.5).toInt()
        val pz = (z + 8.5).toInt()
        return Pos(
            px - Math.floorMod(px, ROOM_SIZE) - 8,
            pz - Math.floorMod(pz, ROOM_SIZE) - 8,
        )
    }

    /** Map pixel of a room's top-left corner, from the room's physical northwest corner. */
    fun physicalToMap(mapEntrance: Pos, mapRoomSize: Int, physicalEntrance: Pos, physical: Pos): Pos {
        val step = mapRoomSize + MAP_GAP
        return Pos(
            (physical.x - physicalEntrance.x) / ROOM_SIZE * step + mapEntrance.x,
            (physical.z - physicalEntrance.z) / ROOM_SIZE * step + mapEntrance.z,
        )
    }

    /** Inverse of [physicalToMap] for room corners. Both anchors sit on the same lattice, so this is exact. */
    fun mapToPhysicalRoom(mapEntrance: Pos, mapRoomSize: Int, physicalEntrance: Pos, mapPos: Pos): Pos {
        val step = mapRoomSize + MAP_GAP
        return Pos(
            (mapPos.x - mapEntrance.x) / step * ROOM_SIZE + physicalEntrance.x,
            (mapPos.z - mapEntrance.z) / step * ROOM_SIZE + physicalEntrance.z,
        )
    }

    /** Inverse of [physicalToMap], continuous — used to turn a map decoration into world coords. */
    fun mapToPhysical(
        mapEntrance: Pos,
        mapRoomSize: Int,
        physicalEntrance: Pos,
        mapX: Double,
        mapY: Double,
    ): Pair<Double, Double> {
        val step = (mapRoomSize + MAP_GAP).toDouble()
        return Pair(
            (mapX - mapEntrance.x) / step * ROOM_SIZE + physicalEntrance.x,
            (mapY - mapEntrance.z) / step * ROOM_SIZE + physicalEntrance.z,
        )
    }

    /** Ticks as `m:ss.t`. One tick is 50 ms, so tenths are exact. */
    fun formatTicks(ticks: Int): String {
        val seconds = ticks / 20
        return "%d:%02d.%d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60, (ticks / 2) % 10)
    }

    /**
     * Splits [points] over the players in [ticksInRoom], proportional to time spent there.
     * Players below [minTicks] only walked through and get nothing.
     */
    fun splitPoints(ticksInRoom: Map<String, Int>, points: Double, minTicks: Int): Map<String, Double> {
        val eligible = ticksInRoom.filterValues { it >= minTicks }
        val total = eligible.values.sum()
        if (total == 0) return emptyMap()
        return eligible.mapValues { (_, ticks) -> points * ticks / total }
    }
}
