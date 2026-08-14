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

    /**
     * Run tick the first party member was seen in here. The anchor [clearedAtTick] needs to become a
     * duration: on its own it only says *when* in the run the checkmark appeared, which is route
     * order rather than how long the room took.
     *
     * First *sighting*, with no minimum stay — somebody crossing the room on the way elsewhere starts
     * the clock just as much as the party that fights here. `clearedAtTick - enteredAtTick` is
     * therefore an upper bound on the clear, never an underestimate. See the `ponytail:` note at the
     * assignment in `discover`.
     */
    var enteredAtTick: Int? = null

    var clearedAtTick: Int? = null
    var secretsAtTick: Int? = null
    var pointsAwarded = false

    /** Secrets found in this room, from the action bar. Party-wide: it says nothing about who. */
    var secretsFound = 0

    /** Of those, the ones that coincided with the local player's own interaction. */
    var ownSecrets = 0

    /** Run tick the room's first secret was taken at, once one has been. */
    var secretRunStart: Int? = null
        private set

    /** Run tick of the most recent secret, which is what [expireSecretRun] measures the gap from. */
    var secretRunLast: Int? = null
        private set

    /** Set once the last secret lands: the finished run, in ticks. Null while it is still open. */
    var secretRunTicks: Int? = null
        private set

    /** A run that will never produce a time — joined late, gone quiet, or nothing to measure. */
    var secretRunDiscarded = false
        private set

    /** Party deaths that happened while the victim was in this room. */
    var deaths = 0

    /**
     * The room already carried a checkmark when we first saw it, so nobody cleared it during our
     * run — the entrance, fairy rooms and empty rooms with no secrets are green from the start.
     * Without this baseline they count as clears and hand a point to whoever happens to stand there.
     */
    var preCleared = false

    val cleared get() = clearedAtTick != null
    val allSecrets get() = secretsAtTick != null

    fun label() = name ?: "${type.name.lowercase().replaceFirstChar { it.uppercase() }} (unknown)"

    /** What one secret did to the room's run. Anything but [DONE] leaves the history untouched. */
    enum class SecretRun { IGNORED, STARTED, RUNNING, DONE, DISCARDED }

    /**
     * The secret run: from the moment the room's **first** secret is taken to the moment its last
     * one is.
     *
     * Deliberately a different clock from the clear record. Presence counts everything you did in
     * the room — walking in, fighting, waiting for a teammate; this measures only the part that is
     * actually raced, from the first chest, lever, essence or pickup to the last secret.
     *
     * A run that does not start at [previous] == 0 is somebody else's leftovers, and a room with a
     * single secret has no span between its ends. Both are discarded rather than recorded as very
     * fast runs — an unrecorded room costs nothing, a bogus record is permanent.
     */
    fun onSecret(previous: Int, found: Int, max: Int, at: Int): SecretRun {
        if (secretRunTicks != null || secretRunDiscarded) return SecretRun.IGNORED
        val started = secretRunStart
        if (started == null) {
            if (previous != 0 || max < 2) {
                secretRunDiscarded = true
                return SecretRun.DISCARDED
            }
            secretRunStart = at
            secretRunLast = at
            // The counter can jump straight to full when two secrets land in one bar update. A run
            // whose ends are the same event is not a time anybody raced.
            if (found >= max) {
                secretRunDiscarded = true
                return SecretRun.DISCARDED
            }
            return SecretRun.STARTED
        }
        secretRunLast = at
        if (found < max) return SecretRun.RUNNING
        // Clamped: an own click can sit slightly before the bar update it is credited to.
        secretRunTicks = (at - started).coerceAtLeast(0)
        return SecretRun.DONE
    }

    /**
     * No further secret for [abandonTicks]: the party moved on and left the room unfinished, so the
     * run is dropped instead of being closed at whatever the room reaches later. Returns whether
     * this call is the one that discarded it, so the caller logs it exactly once.
     */
    fun expireSecretRun(now: Int, abandonTicks: Int): Boolean {
        if (secretRunTicks != null || secretRunDiscarded) return false
        val last = secretRunLast ?: return false
        if (now - last <= abandonTicks) return false
        secretRunDiscarded = true
        return true
    }

    /** The finished run, or the clock as it stands for the HUD. Null when there is nothing to show. */
    fun secretRunElapsed(now: Int): Int? {
        secretRunTicks?.let { return it }
        if (secretRunDiscarded) return null
        return secretRunStart?.let { (now - it).coerceAtLeast(0) }
    }
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

    /** Where each member was last seen, so a death in the tab list can be charged to a room. */
    private val lastCell = HashMap<String, Pos>()

    var roomsCleared = 0
        private set

    var deaths = 0
        private set

    fun reset() {
        rooms.clear()
        credited.clear()
        identified.clear()
        unidentifiable.clear()
        // Cleared on server transfer, so chunks buffered for the previous world are never hashed.
        pendingChunks.clear()
        lastCell.clear()
        roomsCleared = 0
        deaths = 0
    }

    /**
     * A party member's tab class flipped to DEAD. Charged to the room they were last seen in — the
     * map decoration is already gone by the time the tab list catches up.
     */
    fun onDeath(player: String) {
        deaths++
        val room = lastCell[player]?.let { rooms[it] }
        room?.let { it.deaths++ }
        DebugLog.event("death", "player" to Pseudonym.of(player), "room" to room?.label())
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

    /**
     * The cleared rooms nobody was credited for: [roomsCleared] minus everything [pointsByPlayer]
     * handed out, [settle]d.
     *
     * One expression in one place. The run report and the end-of-run summary both report this
     * number and both used to compute it inline, which is how they came to disagree about what
     * counts as zero.
     */
    fun unattributed(): Double = settle(roomsCleared - credited.values.sum())

    /**
     * Rounds an unattributed-points figure to the two decimals every path that displays it already
     * truncates to, and never returns a negative.
     *
     * [POINTS_PER_ROOM] is split across a room's players by tick count, so a room shared three ways
     * credits 0.333… three times and those three do not add back up to the point they came from.
     * Over the ~30 rooms of a floor the credited total ends up a few ULPs off, and
     * `roomsCleared - total` is then a residue of ±1e-15 rather than the 0 it means. A real report
     * reached the server carrying `"unattributed": 3.552713678800501e-15`; `profiles/` is
     * append-only, so that line can never be corrected.
     *
     * Rounding rather than a threshold-to-zero, because the residue turns up with either sign and
     * rounding removes both — the clamp this replaces only ever caught the negative half. It also
     * makes the number the server stores forever the same number the player was shown. Two decimals
     * costs nothing: this counts rooms, and no display has ever offered a third.
     *
     * A genuinely non-zero value survives untouched. A room that cleared with nobody ever seen in it
     * really is an unattributed point, and the gap between this and `roomsCleared` is the built-in
     * diagnostic for a broken decoration→player mapping — blanket-zeroing the field would delete the
     * signal along with the noise.
     *
     * [Math.round] rather than `roundToLong`, which throws on NaN. Nothing here can produce one, but
     * the cost of being total is a character and the cost of being wrong is permanent.
     */
    fun settle(points: Double): Double = (Math.round(points * 100.0) / 100.0).coerceAtLeast(0.0)

    fun roomAt(cell: Pos): TrackedRoom? = rooms[cell]

    fun visitedRooms(): List<TrackedRoom> = rooms.values.distinct()

    fun tick(client: Minecraft, map: MapItemSavedData) {
        for ((name, cell) in PartyTracker.positions(map)) {
            lastCell[name] = cell
            val room = rooms[cell] ?: discover(map, cell) ?: continue
            room.ticks.merge(name, 1, Int::plus)
        }

        client.level?.let { drainChunks(it) }
        applyNames()

        for (room in rooms.values.distinct()) {
            // Expired here rather than in the action bar path: the bar stops updating at exactly the
            // moment the room goes quiet, so a run left open there would never time out.
            SecretTracker.expireRun(room, DungeonSession.runTicks)
            if (room.allSecrets) continue // nothing left to observe
            val checkmark = DungeonMapReader.checkmarkColor(map, room.mapSegments, DungeonSession.mapRoomSize, room.type.color)
            if (checkmark != DungeonMapReader.WHITE && checkmark != DungeonMapReader.GREEN) continue

            if (!room.cleared) {
                room.clearedAtTick = DungeonSession.runTicks
                roomsCleared++
                DebugLog.event(
                    "cleared",
                    "room" to room.label(), "type" to room.type, "checkmark" to checkmark,
                    "ticks" to Pseudonym.keys(room.ticks).toString(),
                )
                award(room)
                RoomHistory.onRoomCleared(room)
            }
            if (checkmark == DungeonMapReader.GREEN && !room.allSecrets) {
                room.secretsAtTick = DungeonSession.runTicks
                // Records no longer hang off this: the map's confirmation arrives whenever it
                // arrives, while the secret run is timed from the secrets themselves. This stays as
                // the run report's timeline and as the signal that the room is done.
                DebugLog.event(
                    "all_secrets",
                    "room" to room.label(), "afterClear" to (DungeonSession.runTicks - (room.clearedAtTick ?: 0)),
                    "expectedSecrets" to (room.info?.secrets ?: -1),
                    "secretRunTicks" to room.secretRunTicks,
                )
            }
        }
    }

    private fun award(room: TrackedRoom) {
        if (room.pointsAwarded) return
        room.pointsAwarded = true
        val split = DungeonGrid.splitPoints(room.ticks, POINTS_PER_ROOM, MIN_TICKS)
        if (split.isNotEmpty()) {
            split.forEach { (name, points) -> credited.merge(name, points, Double::plus) }
            DebugLog.event("award", "room" to room.label(), "split" to Pseudonym.keys(split).toString())
            return
        }
        // Nobody cleared the one-second bar. In a party that filter is right — whoever did the work
        // is in the room longer than the person walking past. Solo there is nobody else, so the
        // point simply vanished and a run's points no longer added up to its rooms: an M7 dropped
        // three that way, all of them empty rooms that clear the moment you step in. Falls back to
        // raw presence and logs it as such, so a fallback stays visible in the data instead of
        // hiding inside a normal award.
        val fallback = DungeonGrid.splitPoints(room.ticks, POINTS_PER_ROOM, minTicks = 1)
        fallback.forEach { (name, points) -> credited.merge(name, points, Double::plus) }
        DebugLog.event(
            "unattributed",
            "room" to room.label(), "ticks" to Pseudonym.keys(room.ticks).toString(),
            "fallback" to Pseudonym.keys(fallback).toString(),
        )
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

        // Discovery only happens for a cell a party member is standing in (see tick), so this is the
        // first entry rather than the moment the map revealed the room.
        // ponytail: two ceilings on this anchor, both of which only ever make a clear look longer or
        // shorter than it was, never wrong in a way the number admits to.
        // 1. No minimum stay: any sighting starts the clock, so a member who walks through a room the
        //    party clears twenty seconds later anchors it at the walk-through. MIN_TICKS (1s) is the
        //    threshold attribution already uses for exactly this, and applying it here is the upgrade
        //    path — it costs a schema bump, because profiles/ is append-only and the same field would
        //    otherwise mean two things in one average. Until then `clear` is an upper bound: fine for
        //    ranking rooms against each other, not a difficulty weight anybody should trust blind.
        // 2. Presence comes from map decorations, so the clock starts when the decoration resolves —
        //    the same ceiling room.ticks already counts under, and a room entered while the decoration
        //    lags reads as marginally faster. Upgrade path is party sync, which would replace
        //    decoration reading altogether (see PartyTracker's ponytail).
        room.enteredAtTick = DungeonSession.runTicks

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
