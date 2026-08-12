package sighteaddons

import net.minecraft.client.Minecraft
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.maps.MapItemSavedData

/**
 * One room of the current run: who was in it for how long, when it was cleared, and when all
 * of its secrets were found.
 *
 * [clearedAtTick] and [secretsAtTick] are independent. White checkmark means cleared with secrets
 * still missing; green means cleared *and* all secrets found. A room therefore usually gets a clear
 * timestamp first and a secrets timestamp later — or never, if the party leaves secrets behind.
 */
class TrackedRoom(val type: RoomType, val mapSegments: Set<Pos>, val cells: Set<Pos>) {
    /** Resolved from the room database once the local player stands in it. */
    var name: String? = null
    var info: RoomInfo? = null

    /** Accumulated presence per player, in ticks. Also the basis for point attribution. */
    val ticks = HashMap<String, Int>()

    var clearedAtTick: Int? = null
    var secretsAtTick: Int? = null
    var pointsAwarded = false

    /** Secrets found in this room, from the action bar. Party-wide: it says nothing about who. */
    var secretsFound = 0

    /** Of those, the ones that coincided with the local player's own interaction. */
    var ownSecrets = 0

    /**
     * The room already carried a checkmark when we first saw it, so nobody cleared it during our
     * run — the entrance, fairy rooms and empty rooms with no secrets are green from the start.
     * Without this baseline they count as clears and hand a point to whoever happens to stand there.
     */
    var preCleared = false

    val cleared get() = clearedAtTick != null
    val allSecrets get() = secretsAtTick != null

    fun label() = name ?: "${type.name.lowercase().replaceFirstChar { it.uppercase() }} (unknown)"
}

/**
 * Attributes room clears to the party members who were actually in the room, and records the
 * clear / all-secrets timeline per room.
 *
 * Phase 1 gives every room one point. Phase 2 replaces that with per-room ClearPoints, which is
 * the whole reason this exists: Hypixel's own score weights every room equally regardless of
 * difficulty. The room database already carries the secret and crypt counts needed for it.
 */
object ContributionTracker {
    const val MIN_TICKS = 20 // 1s; below this a member only passed through
    private const val POINTS_PER_ROOM = 1.0

    /**
     * The dungeon grid sits at fixed world coordinates, so the chunks holding the sample columns
     * are always the even chunks in this range — six per axis, one per room column.
     */
    private val DUNGEON_CHUNKS = -12..-2

    /** Keyed by every physical segment of the room, so any segment resolves to the same room. */
    private val rooms = HashMap<Pos, TrackedRoom>()
    private val credited = HashMap<String, Double>()

    /** Grid cell -> room identity, filled as chunks stream in. Independent of calibration. */
    private val identified = HashMap<Pos, RoomInfo>()
    private val pendingChunks = HashSet<Pos>()

    /**
     * Cells whose column hashed to nothing in the database. Terrain is static, so a miss stays a
     * miss — remembering them stops the chunk streaming from re-hashing and re-logging the filler
     * cells outside the run's layout on every reload.
     */
    private val unidentifiable = HashSet<Pos>()

    var roomsCleared = 0
        private set

    fun reset() {
        rooms.clear()
        credited.clear()
        identified.clear()
        unidentifiable.clear()
        // Cleared on server transfer, so chunks buffered for the previous world are never hashed.
        pendingChunks.clear()
        roomsCleared = 0
    }

    /**
     * Queues a loaded chunk for room identification. Called for every chunk, including before we
     * know we are in a dungeon — those get drained on the first in-dungeon tick.
     */
    fun onChunkLoad(chunkX: Int, chunkZ: Int) {
        if (chunkX % 2 != 0 || chunkZ % 2 != 0) return
        if (chunkX !in DUNGEON_CHUNKS || chunkZ !in DUNGEON_CHUNKS) return
        pendingChunks.add(Pos(chunkX, chunkZ))
    }

    fun pointsByPlayer(): Map<String, Double> = credited

    fun roomAt(cell: Pos): TrackedRoom? = rooms[cell]

    fun visitedRooms(): List<TrackedRoom> = rooms.values.distinct()

    fun tick(client: Minecraft, map: MapItemSavedData) {
        for ((name, cell) in PartyTracker.positions(map)) {
            val room = rooms[cell] ?: discover(map, cell) ?: continue
            room.ticks.merge(name, 1, Int::plus)
        }

        client.level?.let { drainChunks(it) }
        applyNames()

        for (room in rooms.values.distinct()) {
            if (room.allSecrets) continue // nothing left to observe
            val checkmark = DungeonMapReader.checkmarkColor(map, room.mapSegments, DungeonSession.mapRoomSize, room.type.color)
            if (checkmark != DungeonMapReader.WHITE && checkmark != DungeonMapReader.GREEN) continue

            if (!room.cleared) {
                room.clearedAtTick = DungeonSession.runTicks
                roomsCleared++
                DebugLog.event(
                    "cleared",
                    "room" to room.label(), "type" to room.type, "checkmark" to checkmark,
                    "ticks" to room.ticks.toString(),
                )
                award(room)
                RoomHistory.onRoomEvent(room, secrets = false)
            }
            if (checkmark == DungeonMapReader.GREEN && !room.allSecrets) {
                room.secretsAtTick = DungeonSession.runTicks
                DebugLog.event(
                    "all_secrets",
                    "room" to room.label(), "afterClear" to (DungeonSession.runTicks - (room.clearedAtTick ?: 0)),
                    "expectedSecrets" to (room.info?.secrets ?: -1),
                )
                RoomHistory.onRoomEvent(room, secrets = true)
            }
        }
    }

    private fun award(room: TrackedRoom) {
        if (room.pointsAwarded) return
        room.pointsAwarded = true
        val split = DungeonGrid.splitPoints(room.ticks, POINTS_PER_ROOM, MIN_TICKS)
        split.forEach { (name, points) -> credited.merge(name, points, Double::plus) }
        if (split.isEmpty()) {
            // Nobody stayed long enough — this is the unattributed remainder, worth seeing.
            DebugLog.event("unattributed", "room" to room.label(), "ticks" to room.ticks.toString())
        } else {
            DebugLog.event("award", "room" to room.label(), "split" to split.toString())
        }
    }

    /**
     * Hashes the sample column of every newly loaded dungeon chunk. This is what gives rooms a name
     * before anyone walks into them — the whole dungeon is only 12x12 chunks, so in practice most
     * of it is identified from the chunks Hypixel streams anyway, not just the room you stand in.
     */
    private fun drainChunks(level: Level) {
        if (pendingChunks.isEmpty()) return
        val iterator = pendingChunks.iterator()
        while (iterator.hasNext()) {
            val chunk = iterator.next()
            iterator.remove()
            // Sample column is 15 blocks into the cell, and lands 7 blocks into an even chunk.
            val cell = Pos(chunk.x * 16 - 8, chunk.z * 16 - 8)
            if (cell in identified || cell in unidentifiable) continue

            val column = RoomDatabase.columnAt(level, cell)
            val info = RoomDatabase.lookup(column.hashCode())
            if (info == null) {
                unidentifiable.add(cell)
                // An all-'0' column is an empty grid cell outside the layout, not a failure worth
                // reporting. Anything else means the port or the database drifted, so keep the raw
                // column: it is the only way to tell those two apart afterwards.
                if (column.any { it != '0' }) {
                    DebugLog.event("room_unmatched", "cell" to cell, "core" to column.hashCode(), "column" to column)
                }
                continue
            }
            identified[cell] = info
            DebugLog.event(
                "room_identified",
                "cell" to cell, "name" to info.name, "type" to info.type,
                "shape" to info.shape, "secrets" to info.secrets,
            )
        }
    }

    /** Any identified segment names the whole room. */
    private fun applyNames() {
        for (room in rooms.values.distinct()) {
            if (room.name != null) continue
            room.cells.firstNotNullOfOrNull { identified[it] }?.let {
                room.info = it
                room.name = it.name
            }
        }
    }

    /** Registers the room at [cell] on first visit by anyone, including its other segments. */
    private fun discover(map: MapItemSavedData, cell: Pos): TrackedRoom? {
        val mapEntrance = DungeonSession.mapEntrance ?: return null
        val physicalEntrance = DungeonSession.physicalEntrance ?: return null
        val roomSize = DungeonSession.mapRoomSize
        val mapPos = DungeonGrid.physicalToMap(mapEntrance, roomSize, physicalEntrance, cell)
        val type = RoomType.fromColor(DungeonMapReader.colorAt(map, mapPos)) ?: return null
        // The map has not revealed this room yet, so its type and extent are still unknown.
        // Don't cache a wrong shape — retry next tick, by which point standing there reveals it.
        if (type == RoomType.UNKNOWN) return null

        // Only plain rooms span more than one segment; every other type is 1x1.
        val mapSegments = if (type == RoomType.ROOM) {
            DungeonMapReader.roomSegments(map, mapPos, roomSize, type.color)
        } else {
            setOf(mapPos)
        }

        val cells = mapSegments.map { DungeonGrid.mapToPhysicalRoom(mapEntrance, roomSize, physicalEntrance, it) }.toSet()
        val room = TrackedRoom(type, mapSegments, cells)
        cells.forEach { rooms[it] = room }

        // Baseline: a checkmark that is already there was not earned during this run.
        val existing = DungeonMapReader.checkmarkColor(map, mapSegments, roomSize, type.color)
        if (existing == DungeonMapReader.WHITE || existing == DungeonMapReader.GREEN) {
            room.preCleared = true
            room.pointsAwarded = true // so award() can never fire for it
            room.clearedAtTick = DungeonSession.runTicks
            if (existing == DungeonMapReader.GREEN) room.secretsAtTick = DungeonSession.runTicks
        }

        DebugLog.event(
            "room_discovered",
            "type" to type, "segments" to cells.size, "cells" to cells.toString(), "mapPos" to mapPos,
            "preCleared" to room.preCleared,
        )
        // A chunk-derived name may already be waiting for this room.
        cells.firstNotNullOfOrNull { identified[it] }?.let {
            room.info = it
            room.name = it.name
        }
        return room
    }
}
