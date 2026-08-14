package sighteaddons

import net.minecraft.client.Minecraft
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import kotlin.math.pow

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
 * time as before. Since `clearpoints-002` "what it took" is *measured* rather than declared: the
 * room's clear time against the distribution of clear times, blended out of a seed estimate as the
 * measurement earns it ([blend]). Where the measurement comes from is [RoomStats].
 *
 * **Two units live in here and they are not interchangeable.** [roomsCleared] and [unattributed]
 * count *rooms*; [pointsByPlayer] carries *weighted points*. Before ClearPoints they happened to
 * agree, because a room was worth exactly 1.0 — see [unattributed] for what that coincidence cost
 * and why nothing subtracts one from the other any more.
 */
object ContributionTracker {
    const val MIN_TICKS = 20 // 1s; below this a member only passed through

    /**
     * The seed base for an ordinary room, and the user's own number: *"alle anderen Raeume haben
     * einen Base Score von 0.75"*. Deliberately not zero — no room becomes worthless, and an unnamed
     * room (chunk never streamed, so [TrackedRoom.info] is null) still pays.
     */
    private const val ORDINARY_SEED = 0.75

    /** The seed base for a puzzle the user did not name individually. See [SEED_BY_NAME]. */
    private const val PUZZLE_SEED = 1.0

    /**
     * The seed bases the user gave by name, 2026-08-14, verbatim. **The keys are `rooms.json`'s
     * spelling** — `Ice Fill` and `Water Board`, two words each — because that is the string the
     * database carries, the string the report ships and the string the receiver folds on. A typo
     * here does not fail: it silently misses, and the room quietly falls back to [PUZZLE_SEED].
     * `the seeded rooms are spelled the way the database spells them` in `RoomDatabaseTest` is the
     * guard, and it checks against the bundled `rooms.json` rather than against this map.
     *
     * `Quiz` is listed even though its value equals [PUZZLE_SEED] today, so this map is the user's
     * table one-for-one and a reader comparing the two does not have to work out why a row is
     * missing. That the two numbers coincide is not an invariant.
     */
    private val SEED_BY_NAME = mapOf(
        "Ice Fill" to 2.0,
        "Water Board" to 1.5,
        "Quiz" to 1.0,
    )

    /**
     * What a room measuring exactly the median clear time is worth. The median room *is* the
     * ordinary room, so this is [ORDINARY_SEED] rather than a second opinion about it — which is
     * what makes the measured scale and the seed scale the same scale, and the blend in [blend] a
     * blend rather than a mixture of two units.
     */
    private const val MEDIAN_BASE = ORDINARY_SEED

    /**
     * How steeply a room's base follows its clear time, as an exponent on the ratio to the median:
     * **0.5, so four times the time is twice the base.**
     *
     * Not linear, and the reason is measured rather than aesthetic. The only real clear averages
     * that exist run from 0.75 s to 36.5 s — a spread of about 49x — while the user's own seed
     * estimates span 0.75 to 2.0, a spread of 2.7x. A linear map calibrated on the median would put
     * the slowest room at ten times the ordinary base and the clamp below would then be deciding
     * almost every room, which is a constant wearing a measurement's clothes. The square root maps
     * that 49x spread of time onto a 7x spread of base, which is the order of magnitude the
     * estimates live at, and leaves the clamp as a guard against absurdity instead of as the rule.
     */
    private const val TIME_EXPONENT = 0.5

    /**
     * The clamp on the measured base, both ends, so no single average can run away with a room.
     *
     * [MIN_BASE] is a third of [ORDINARY_SEED]: a room you cross in a moment is worth much less than
     * an ordinary one and never nothing, because somebody still cleared it. [MAX_BASE] is 1.25x the
     * largest seed the user gave (`Ice Fill`, 2.0) — measurement is allowed to say a room is harder
     * than the hardest thing they named, since that is the information this feature exists to
     * collect, but not to say it is five times harder on the strength of one 36-second outlier.
     *
     * Both are deliberately wide enough that they normally do not bind: at [TIME_EXPONENT] 0.5 the
     * top clamp needs a room 11x the median and the bottom one a room under a ninth of it. A clamp
     * that binds on ordinary rooms would be the weighting.
     */
    private const val MIN_BASE = 0.25
    private const val MAX_BASE = 2.5

    /**
     * The shrinkage constant `k` in `w = n / (n + k)`: **the sample count at which the measurement
     * and the seed carry equal weight.**
     *
     * This is the whole reason the feature can ship on estimated seeds. `n = 0` is exactly the seed,
     * a large `n` is essentially the measurement, and everything between is a smooth blend — so the
     * values improve themselves as runs accumulate instead of improving when somebody edits a
     * constant. Critically there is **no cliff**: `w` is continuous in `n`, so no room's worth jumps
     * when its ninth clear becomes its tenth. `a room's base never jumps as its sample count grows`
     * is the guard.
     *
     * 10 rather than 1 or 100 because the blocker this feature carried was precisely that one
     * observation is worth less than a considered estimate: at `n = 1` the measurement moves the
     * base by 9%, at `n = 10` it is half, at `n = 30` it is three quarters. On the box's current
     * rate — 157 room visits over 9 runs, spread across 83 rooms — ten clears of one room is
     * roughly forty runs. A weight that took a season of play to turn over is the intended speed;
     * these numbers should move slower than a mood.
     */
    private const val CONFIDENCE_SAMPLES = 10.0

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
     * [roomsCleared]. One [weightOf] room is worth between [MIN_BASE] and [MAX_BASE] plus a quarter
     * per secret, so this total normally runs above the room count. See [unattributed].
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
     *     weight = base(room) + 0.25 per secret the database says the room holds
     *     base    = seed, blended toward the room's measured clear time as samples accumulate
     *
     * **What `clearpoints-002` changed, and it is a change of kind rather than of numbers.** The
     * previous version paid a room for *being* a puzzle (1.5), a trap, a miniboss, a blood room
     * (1.0 each) and for every segment past the first (0.5). Five hand-picked constants, and a
     * constant is an opinion. Those are gone. Size and kind are now **emergent**: a four-segment
     * room scores high because crossing it measures slow, and a puzzle scores high because puzzles
     * measure slow — not because a number here says they should. What replaces them is [seedOf], a
     * table of estimates the user supplied, which [blend] moves toward the measurement as the
     * measurement earns it.
     *
     * **Old scores are not comparable with new ones and nothing converts between them.** On the one
     * real M7 there is, the old formula scored rooms from 1.00 (`Hall`) to 4.50 (`Cathedral`), and
     * `Pipes` — a 1x4 holding seven secrets — was `1.0 + 7x0.25 + 3x0.5 = 4.25`. Under this model
     * `Pipes` seeds at `0.75 + 7x0.25 = 2.50` before any measured adjustment, because its size stops
     * being paid for directly. Every room came down, and rooms came down by different amounts, so a
     * standing from an older build cannot be held next to one from this build. `the seed weight of
     * Pipes is the user's model, not the old one` in `RoomDatabaseTest` pins that number against the
     * real database.
     *
     * **Two things are still properties of the room rather than of what the party did in it**, so
     * two players in the same room are still separated only by time, which is [award]'s job:
     *
     * - **Time** ([blend]): how long the average player takes to clear this room, from the
     *   receiver's `clearStay` average — the stay-anchored clear span, with no secret hunting in it.
     * - **Secrets** ([SECRET_POINTS]): from the room database, and the reason `rooms.json` carries
     *   the counts at all.
     *
     * **Floor is deliberately not a factor**, though `DungeonSession.floorNumber` is right there and
     * the README lists it as available. A floor multiplier is constant across every room of a run,
     * so it scales every player's total by the same number and cannot separate anybody — and points
     * are only ever compared *within* one run, since they are shown on the HUD and in the run
     * summary and are not part of the run report at all (`RUN_KEYS` in the receiver's `ingest.py`
     * has no points field; the per-player breakdown never leaves the client). It would be a constant
     * with no reader. Revisit if points ever become something the server stores.
     *
     * **All three exclusions are guarded.** Rarity by `a rare room is not paid for being rare`, the
     * live secret counter by `the live secret counter is not what a room is worth`, and the floor by
     * `a room is worth the same on every floor`, which writes `DungeonSession.floor` by reflection:
     * the field is `private set` and set only by `inDungeon(Minecraft)`, which no test here can call,
     * but the backing field of the `object` is reachable and `floorNumber` then reads the real digit.
     * Measured — a floor multiplier added to this function fails that case and **nothing else in the
     * suite**, in both forms it could take: one drawn from `floorNumber`, and one drawn from the
     * floor *string*, which is how a master-mode bonus would have to be written since `floorNumber`
     * cannot tell `F7` from `M7`.
     *
     * Earlier revisions of this KDoc said a floor guard was impossible here and would be "a guard in
     * name only". That was wrong, and it was recorded in six places before an evaluator disproved it
     * by writing the test. Nothing about the exclusion changed; only the claim that it could not be
     * checked. `a room is worth the same however far the run has got` stays as a separate, narrower
     * guard — it covers a factor drawn from *run progress*, and on a floor multiplier it catches
     * nothing.
     *
     * An unnamed room — the chunk never streamed, so [TrackedRoom.info] is null — keeps the seed its
     * map colour implies, pays no secret bonus, and can carry no measurement, since the measurement
     * is keyed by name. It is worth less than it should be, never nothing: a room we could not
     * identify is still a room somebody cleared.
     */
    internal fun weightOf(room: TrackedRoom): Double {
        val scores = RoomStats.scores
        // The database's name, not [TrackedRoom.name], because the database's spelling is what the
        // report ships and what the receiver folds its averages under. They are the same string
        // whenever both are set — `applyNames` copies one from the other — so the fallback only
        // matters for a room identified but not yet named.
        val name = room.info?.name ?: room.name
        return blend(seedOf(room), name?.let { scores.of(it) }, scores.medianTicks) +
            (room.info?.secrets ?: 0) * SECRET_POINTS
    }

    /**
     * The estimated base for a room before any measurement, from the table the user supplied on
     * 2026-08-14 — *"Das sind erstmal nur geschaetzte Werte, ich moechte dass die Logic trotzdem in
     * Kraft tritt, dass sie die Werte somit immer verbessern."*
     *
     * A **prior, not a constant.** These are the numbers a room is worth when nothing has been
     * measured about it, and [blend] is what makes them temporary. That distinction is the feature:
     * the five kind and size constants this replaces could only ever be improved by somebody editing
     * this file, whereas a seed is improved by playing.
     *
     * Two rooms are named individually and everything else falls out of the kind, which is either
     * the database's vocabulary or — for a room whose chunk never streamed — the map colour's. The
     * two vocabularies now only have to agree on one word, `PUZZLE`, which they do; the
     * `CHAMPION`/`MINIBOSS` disagreement that used to matter is moot, because neither is paid for
     * being what it is any more.
     */
    internal fun seedOf(room: TrackedRoom): Double {
        SEED_BY_NAME[room.info?.name ?: room.name]?.let { return it }
        return if ((room.info?.type ?: room.type.name) == "PUZZLE") PUZZLE_SEED else ORDINARY_SEED
    }

    /**
     * Moves a room's [seed] toward what it actually measures, by however much the measurement has
     * earned. **This is the point of the feature**, and it is why estimated seeds were shippable at
     * all: the values correct themselves as runs accumulate rather than when somebody edits a
     * number.
     *
     *     measured = 0.75 * (avgTicks / median) ^ 0.5,  clamped to [0.25, 2.5]
     *     w        = n / (n + 10)
     *     base     = seed + w * (measured - seed)
     *
     * Three properties, each of which is a test:
     *
     * - **`n = 0` — or no sample, or nothing measured anywhere — is exactly the seed.** Not
     *   approximately: an unmeasured room must not be silently treated as a fast one, so the absent
     *   case returns [seed] untouched rather than a default measurement.
     * - **A large `n` is essentially the measurement.** At `n = 1000` the seed contributes under 1%.
     * - **No cliff.** `w` is continuous in `n`, so there is no sample count at which a room's worth
     *   jumps — the ninth clear and the tenth differ by two percent of the gap, not by a step.
     *
     * The measurement is normalised against the distribution rather than read as seconds, so a
     * weight says where a room stands among rooms. That is what makes it commensurable with a seed
     * at all, and it is why [MEDIAN_BASE] is [ORDINARY_SEED] rather than a second number: the median
     * room *is* the ordinary room. See [TIME_EXPONENT] for the shape and [MIN_BASE]/[MAX_BASE] for
     * the clamp, which exists so one 36-second outlier cannot make a room worth ten.
     *
     * Pure, and takes its inputs rather than reaching for [RoomStats], for two reasons: every
     * property above is then testable without a scores file on disk, and the layer the scores came
     * from — fetched, cached or seeded — is deliberately not something the model can see.
     */
    internal fun blend(seed: Double, sample: RoomSample?, medianTicks: Double?): Double {
        if (sample == null || sample.n < 1 || sample.avgTicks <= 0.0) return seed
        if (medianTicks == null || medianTicks <= 0.0) return seed
        val measured = (MEDIAN_BASE * (sample.avgTicks / medianTicks).pow(TIME_EXPONENT))
            .coerceIn(MIN_BASE, MAX_BASE)
        val confidence = sample.n / (sample.n + CONFIDENCE_SAMPLES)
        return seed + confidence * (measured - seed)
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
                // Which scores produced that number. Once the fetch of layer 1 exists a run's points
                // depend on when the player last launched, and a number nobody can attribute to a
                // scores version afterwards is a number nobody can explain. 0 means the seeds.
                "scoresTs" to RoomStats.scores.generatedTs,
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
