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
     * Run tick the room's clock starts: the beginning of the first stay long enough to be work
     * rather than a walk-through. The anchor [clearedAtTick] needs to become a duration — on its own
     * a checkmark timestamp says *when* in the run it appeared, which is route order rather than how
     * long the room took.
     *
     * **Schema 5 changed what this means, which is why it cost a schema bump** (`RunReport.SCHEMA`).
     * Up to schema 4 it was the first *sighting* of anybody, with no minimum stay, so a member
     * crossing the room on the way elsewhere started the clock just as much as the party that fights
     * here: `clearedAtTick - enteredAtTick` was an upper bound that ran long by however far apart the
     * two were — 145 s for a 1x1 in the receiver's own example. The key is unchanged and the field is
     * still optional to the receiver; only the meaning moved, and `profiles/` is append-only, so both
     * meanings live in it forever and the receiver buckets them apart by `v` (`roomstats.py`,
     * `STAY_ANCHOR_SCHEMA`). Nothing may set this from a bare sighting again.
     *
     * Two ways it gets stamped, in [onPresence] and [anchorOnClear], and both are bounded:
     * a stay that reaches [ContributionTracker.MIN_TICKS] anchors at *its own start*, and a room that
     * clears before anyone gets that far falls back to a stay begun within the last
     * [ContributionTracker.MIN_TICKS] — so the fallback can never manufacture a span longer than one
     * second. When neither applies this stays null, which the receiver reads as "no sample" rather
     * than as a zero. An unrecorded room costs nothing; a bogus record is permanent.
     *
     * Never stamped after the clear, so `enteredAtTick <= clearedAtTick` holds whenever both are set.
     * A [preCleared] room therefore keeps a null anchor for its whole life: it was already done when
     * we arrived, so there is no clear of ours to measure.
     */
    var enteredAtTick: Int? = null
        private set

    /**
     * One member's current stay in this room: when it began, how many ticks of it we have seen, and
     * when we last saw them. Ticks accumulate per stay rather than for the whole run, which is what
     * separates "came back and fought" from "walked through twice".
     */
    private class Stay(val start: Int, var ticks: Int, var lastSeen: Int)

    private val stays = HashMap<String, Stay>()

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

    /**
     * [player] was seen in this room at run tick [at]. Extends their current stay, or begins a new
     * one, and stamps [enteredAtTick] the moment a stay is long enough to count. Returns whether
     * *this* call is the one that anchored the room, so the caller logs it exactly once.
     *
     * The anchor is the stay's **start**, not the tick it qualified at. Waiting for the threshold is
     * how we find out the stay was real; the room's clock still started when that person walked in,
     * and anchoring at `start + MIN_TICKS` would shorten every clear in the data by a flat second.
     *
     * A gap of more than [ContributionTracker.MIN_TICKS] between sightings begins a new stay, so
     * somebody who passes through and returns much later is anchored on the return rather than on the
     * pass-through — the whole point of the change. Shorter gaps are tolerated on purpose:
     * [PartyTracker.positions] deliberately reports no teammate positions at all for the 10-20 ticks
     * around a death, when the marker count and the tab roster disagree, and a stay must not be split
     * by our own blind spot. One second is the same threshold attribution already uses, so this adds
     * no second notion of "long enough".
     *
     * Does nothing once the room is [cleared]: an anchor stamped after the checkmark would describe a
     * stay that cannot have contributed to it, and would read as a negative duration on the server.
     */
    fun onPresence(player: String, at: Int): Boolean {
        var stay = stays[player]
        // `- 1` so the comparison is on the ticks actually missed rather than on the distance between
        // two sightings: consecutive ticks are a gap of zero, and tolerating a full MIN_TICKS of them
        // covers the widest roster-skew window PartyTracker documents.
        if (stay == null || at - stay.lastSeen - 1 > ContributionTracker.MIN_TICKS) {
            stay = Stay(start = at, ticks = 0, lastSeen = at)
            stays[player] = stay
        }
        stay.ticks++
        stay.lastSeen = at
        if (cleared || enteredAtTick != null || stay.ticks < ContributionTracker.MIN_TICKS) return false
        enteredAtTick = stay.start
        return true
    }

    /**
     * The room was just cleared at [at] and no stay ever reached the threshold. Anchors on the
     * earliest stay begun within the last [ContributionTracker.MIN_TICKS] instead. Returns whether it
     * stamped one.
     *
     * Without this the rooms that clear the instant somebody steps into them — the empty 1x1s, three
     * of them in one M7 by the count in [ContributionTracker.award] — would report no anchor at all,
     * and the server's average would be built from every room *except* the fastest ones. That is not
     * sparsity, it is a bias, and a silent one: the mean would come out high and nothing in the data
     * would say why.
     *
     * The window is what makes it safe. Every anchor this can produce is at most
     * [ContributionTracker.MIN_TICKS] before the clear, so the fallback cannot reach back to an old
     * walk-through however flaky the decoration stream was — it can only ever record a clear of under
     * a second, which is exactly the case it exists for. A room with nothing in the window keeps a
     * null anchor and contributes no sample.
     */
    fun anchorOnClear(at: Int): Boolean {
        if (enteredAtTick != null) return false
        val start = stays.values
            .map { it.start }
            .filter { at - it in 0..ContributionTracker.MIN_TICKS }
            .minOrNull() ?: return false
        enteredAtTick = start
        return true
    }

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
 * ClearPoints exists because Hypixel's own score weights every room equally regardless of
 * difficulty: clearing a puzzle and walking into an empty 1x1 count the same. [weightOf] is the
 * replacement — a room is worth what it took, and the split over its members is proportional to
 * time as before.
 *
 * **Two units live in here and they are not interchangeable.** [roomsCleared] and [unattributed]
 * count *rooms*; [pointsByPlayer] carries *weighted points*. Before ClearPoints they happened to
 * agree, because a room was worth exactly 1.0 — see [unattributed] for what that coincidence cost
 * and why nothing subtracts one from the other any more.
 */
object ContributionTracker {
    const val MIN_TICKS = 20 // 1s; below this a member only passed through

    /**
     * What every cleared room is worth before anything is added for what it took. Deliberately not
     * zero: no room becomes worthless, so a run's points never fall below the flat count they
     * replace, and an unnamed room (chunk never streamed, so [TrackedRoom.info] is null) still pays.
     */
    private const val BASE_POINTS = 1.0

    /**
     * Per secret the room database says the room holds — the room's own count, not
     * [TrackedRoom.secretsFound]. Two reasons, and the second one is decisive: what is being
     * weighted is the room rather than the party's thoroughness, and [award] runs on the clear
     * checkmark, at which point the secrets of that room are usually still being collected. Reading
     * the live counter here would weight most rooms at whatever they happened to be part-way
     * through. What the party actually found is already reported per room (`secretsFound`,
     * `ownSecrets`), which is where that analysis belongs.
     */
    private const val SECRET_POINTS = 0.25

    /** Per segment beyond the first: a 2x2 is four times the walking of a 1x1 at the same clear. */
    private const val SEGMENT_POINTS = 0.5

    /**
     * Room-kind bonuses. Only for kinds that impose a *specific piece of work* on top of clearing
     * mobs — a gate the party has to open or a fight it has to win. `RARE` deliberately gets
     * nothing: a rare room is unusual, not hard, and whatever makes it worth entering is its secret
     * count, which [SECRET_POINTS] already pays for.
     */
    private const val PUZZLE_BONUS = 1.5
    private const val TRAP_BONUS = 1.0
    private const val MINIBOSS_BONUS = 1.0
    private const val BLOOD_BONUS = 1.0

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

    /**
     * Of those, the ones that credited nobody at all — nobody was ever seen in them. Counted as it
     * happens rather than derived afterwards; [unattributed] is where that matters.
     */
    var unattributedRooms = 0
        private set

    var deaths = 0
        private set

    fun reset() {
        rooms.clear()
        credited.clear()
        unattributedRooms = 0
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

    /**
     * Weighted ClearPoints per player — **not** a number of rooms, and not comparable with
     * [roomsCleared]. One [weightOf] room is worth between [BASE_POINTS] and about five of them, so
     * this total normally runs well above the room count. See [unattributed].
     */
    fun pointsByPlayer(): Map<String, Double> = credited

    /**
     * The cleared rooms nobody was credited for, **in rooms**. A room nobody was ever seen in is one
     * unattributed room whatever [weightOf] says it was worth.
     *
     * This used to be `roomsCleared - pointsByPlayer().values.sum()`, and that expression was only
     * ever correct because a room was worth exactly 1.0 point: the two units were numerically
     * interchangeable, so a count could be recovered by subtracting a score. ClearPoints ends that.
     * A weighted total is routinely larger than the room count, the subtraction goes negative, and
     * [settle]'s clamp turns every run's answer into a flat `0.0` — no exception, no `400`, no log
     * line, just a field that stops saying anything and looks perfect while doing it. So the count
     * is counted, in [award], where the decision is actually made.
     *
     * Not a cosmetic choice. `unattributed` is read *relative to* `roomsCleared` — it is the
     * receiver's only diagnostic for a broken decoration→player mapping (its
     * `agent/AGENT-PROMPT.md`), and the ratio is meaningless unless both sides are rooms. The
     * receiver's own validator agrees: `_real(x, 0, MAX_CLEARED)`, the same ceiling it bounds
     * `roomsCleared` with.
     *
     * The values this returns are unchanged by ClearPoints, which is why the report schema does not
     * move: under flat weighting every award credited either the full point or nothing, so the old
     * subtraction already produced exactly this count (give or take the float residue `residue-001`
     * removed). Same meaning, same numbers, now by construction instead of by coincidence.
     */
    fun unattributed(): Double = unattributedRooms.toDouble()

    /**
     * What one cleared room is worth, before [award] splits it over the members who were in it.
     *
     * The whole point of ClearPoints: Hypixel's score counts rooms, so a party that cleared the
     * puzzles and a party that walked six empty 1x1s score the same. Three things separate them
     * here, and all three are properties of the *room* rather than of what the party did in it —
     * so two players in the same room are still separated only by time, which is [award]'s job.
     *
     * - **Kind** ([PUZZLE_BONUS] and friends): a gate that has to be opened or a fight that has to
     *   be won, on top of the mobs every room has.
     * - **Secrets** ([SECRET_POINTS]): from the room database, and the reason `rooms.json` carries
     *   the counts at all.
     * - **Size** ([SEGMENT_POINTS]): segments, taken from the map rather than from the database's
     *   `shape` string, so an unnamed room is still sized correctly.
     *
     * **Floor is deliberately not a factor**, though `DungeonSession.floorNumber` is right there and
     * the README lists it as available. A floor multiplier is constant across every room of a run,
     * so it scales every player's total by the same number and cannot separate anybody — and points
     * are only ever compared *within* one run, since they are shown on the HUD and in the run
     * summary and are not part of the run report at all (`RUN_KEYS` in the receiver's `ingest.py`
     * has no points field; the per-player breakdown never leaves the client). It would be a constant
     * with no reader. Revisit if points ever become something the server stores.
     *
     * An unnamed room — the chunk never streamed, so [TrackedRoom.info] is null — falls back to the
     * map colour for its kind and pays no secret bonus. It is worth less than it should be, never
     * nothing: a room we could not identify is still a room somebody cleared.
     */
    internal fun weightOf(room: TrackedRoom): Double {
        val info = room.info
        val segments = (room.cells.size - 1).coerceAtLeast(0)
        return BASE_POINTS +
            kindBonus(info?.type ?: room.type.name) +
            (info?.secrets ?: 0) * SECRET_POINTS +
            segments * SEGMENT_POINTS
    }

    /**
     * The kind bonus for a room described either by the database's vocabulary or, when the room was
     * never identified, by the map colour's. The two agree on every name that matters except the
     * miniboss — `rooms.json` calls it `CHAMPION`, [RoomType] calls it `MINIBOSS` — and the map has
     * no colour for `RARE` at all, since rare rooms are drawn as ordinary ones. Both vocabularies go
     * through one `when` rather than two, so a kind cannot be worth different amounts depending on
     * whether its chunk had loaded yet.
     */
    private fun kindBonus(kind: String): Double = when (kind) {
        "PUZZLE" -> PUZZLE_BONUS
        "TRAP" -> TRAP_BONUS
        "CHAMPION", "MINIBOSS" -> MINIBOSS_BONUS
        "BLOOD" -> BLOOD_BONUS
        else -> 0.0
    }

    /**
     * Rounds an unattributed-points figure to the two decimals every path that displays it already
     * truncates to, and never returns a negative.
     *
     * A room's points are split across its players by tick count, so a room shared three ways
     * credits 0.333… three times and those three do not add back up to the point they came from.
     * Over the ~30 rooms of a floor the credited total ended up a few ULPs off, and
     * `roomsCleared - total` — which is how [unattributed] used to be computed — was then a residue
     * of ±1e-15 rather than the 0 it means. A real report reached the server carrying
     * `"unattributed": 3.552713678800501e-15`; `profiles/` is append-only, so that line can never be
     * corrected.
     *
     * [unattributed] no longer subtracts anything, so it cannot produce that residue any more. This
     * stays because [RunReport.build] is the contract seam rather than a convenience: it settles
     * whatever figure it is handed, including from a caller that computed one itself, and a written
     * profile line is permanent either way.
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
            // Separate from the tick count on purpose: that one is a total for attribution, this is
            // the current stay, and only a stay can say whether somebody was working here.
            if (room.onPresence(name, DungeonSession.runTicks)) {
                DebugLog.event(
                    "room_anchored",
                    "room" to room.label(), "at" to room.enteredAtTick, "by" to Pseudonym.of(name),
                )
            }
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
                // Before clearedAtTick, so the fallback still sees an uncleared room, and before the
                // log line, so "cleared" carries the anchor the report will actually ship.
                val anchoredOnClear = room.anchorOnClear(DungeonSession.runTicks)
                room.clearedAtTick = DungeonSession.runTicks
                DebugLog.event(
                    "cleared",
                    "room" to room.label(), "type" to room.type, "checkmark" to checkmark,
                    "ticks" to Pseudonym.keys(room.ticks).toString(),
                    "enterTick" to room.enteredAtTick, "anchoredOnClear" to anchoredOnClear,
                )
                // Counting the room and awarding its points are one decision, not two: the room
                // count and the unattributed count have to move together or the second stops
                // meaning anything relative to the first.
                onCleared(room)
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

    /**
     * The room just earned its checkmark: count it and hand out its points.
     *
     * Internal rather than private because this is where the two units meet — [roomsCleared] counts
     * rooms and [credited] carries weighted points — and that is exactly the seam that has to be
     * testable. [ContributionTracker.tick] needs a `Minecraft` and a `MapItemSavedData`; this needs
     * a cleared room and nothing else.
     */
    internal fun onCleared(room: TrackedRoom) {
        roomsCleared++
        award(room)
    }

    private fun award(room: TrackedRoom) {
        if (room.pointsAwarded) return
        room.pointsAwarded = true
        val points = weightOf(room)
        val split = DungeonGrid.splitPoints(room.ticks, points, MIN_TICKS)
        if (split.isNotEmpty()) {
            split.forEach { (name, earned) -> credited.merge(name, earned, Double::plus) }
            DebugLog.event(
                "award",
                "room" to room.label(), "points" to points,
                "split" to Pseudonym.keys(split).toString(),
            )
            return
        }
        // Nobody cleared the one-second bar. In a party that filter is right — whoever did the work
        // is in the room longer than the person walking past. Solo there is nobody else, so the
        // point simply vanished and a run's points no longer added up to its rooms: an M7 dropped
        // three that way, all of them empty rooms that clear the moment you step in. Falls back to
        // raw presence and logs it as such, so a fallback stays visible in the data instead of
        // hiding inside a normal award.
        val fallback = DungeonGrid.splitPoints(room.ticks, points, minTicks = 1)
        fallback.forEach { (name, earned) -> credited.merge(name, earned, Double::plus) }
        // The one place a room is decided to be unattributed, and therefore the one place it is
        // counted. Empty means nobody was ever seen in this room at all — not that they were only
        // seen briefly, which the fallback above has just paid for. Counted in rooms, not in
        // [points]: what the room was worth says nothing about how many rooms went unattributed,
        // and `unattributed` is read against `roomsCleared`.
        if (fallback.isEmpty()) unattributedRooms++
        DebugLog.event(
            "unattributed",
            "room" to room.label(), "points" to points,
            "ticks" to Pseudonym.keys(room.ticks).toString(),
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

        // No anchor is stamped here any more. Discovery is a first *sighting*, which is precisely the
        // schema-4 meaning schema 5 removed: the room's clock starts on a stay, so it is TrackedRoom
        // .onPresence — called for this same tick right after discover returns — that decides when.
        // ponytail: one ceiling left on the anchor, and it only ever makes a clear look marginally
        // shorter than it was. Presence comes from map decorations, so a stay begins when the
        // decoration resolves rather than when the player crossed the threshold — the same ceiling
        // room.ticks already counts under. Upgrade path is party sync, which would replace decoration
        // reading altogether (see PartyTracker's ponytail).

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
