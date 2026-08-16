package sighteaddons

/**
 * Where a run's time went that was neither clearing a room nor racing its secrets: standing in a
 * room that is already done, and walking between rooms.
 *
 * **Two counters and not one, at the user's decision.** Standing still in a finished room and
 * walking slowly are different problems with different fixes, and a single number cannot say which
 * one you have. The definitions are written down once, for both halves of the pair, in the
 * receiver's `SETUP.md` section 4 ("Schema 6: where a run's time went"), and this file implements
 * that text rather than a second reading of it:
 *
 * - [idleTicks] — run ticks the local player was inside a room that was **already cleared** (a
 *   [TrackedRoom.preCleared] one counts) with **no secret run active**.
 * - [navTicks] — run ticks the local player was inside **no room at all**: corridors, doorways, the
 *   map's dead space.
 *
 * Everything else is work — a room still being cleared, or a cleared room whose secret run is open —
 * and moves neither counter. So the two are a *lower* bound on wasted time and never an accounting
 * of the whole run: `idleTicks + navTicks <= runTicks`, with the difference being the part that was
 * being played. The receiver deliberately does not enforce that inequality (a `400` costs the whole
 * run permanently while an implausible number is merely visible in the fold), which is why this side
 * is the only place it actually holds.
 *
 * **The boss fight advances `runTicks` and neither counter, on purpose.** `SighteAddons.onTick`
 * returns before room sampling once `DungeonSession.update` says we are in the boss, while
 * `tickClock` keeps running so the summary reports the real run time — and the boss is not a room,
 * so counting it as navigation would make every run look like an hour of walking. (The ticks before
 * calibration are outside both: the clock does not advance there either.)
 *
 * Pure where it can be. [classify] is a total function of one room reference and is what
 * `IdleTimeTest` drives; [tick] is one `when` over it. The half that cannot be verified here is the
 * wiring — that `SighteAddons.onTick` hands this the room the local player is actually standing in,
 * once per tick, and only inside a calibrated run — because that needs a live `Minecraft` and the
 * dev client cannot reach Hypixel.
 */
object IdleTime {
    /**
     * What one tick of the run was. [WORKING] is everything that is neither counter — named rather
     * than left as an `else`, so [classify] answers the question in full and a reader can see that
     * the third case exists.
     */
    enum class Where { WORKING, IDLE, NAVIGATING }

    /**
     * `@Volatile` for the same reason [DungeonSession.floor] is: `ClientPlayConnectionEvents
     * .DISCONNECT` writes a run report from a Netty event-loop thread (see [RunReport.uploader]) and
     * these two fields are in it. Only the client thread ever *writes* them — one `++` per tick from
     * `SighteAddons.onTick` — so there is no read-modify-write race to lose, and the volatile buys
     * the off-thread reader a value that is current rather than one cached from an arbitrary earlier
     * tick.
     */
    @Volatile
    var idleTicks = 0
        private set

    @Volatile
    var navTicks = 0
        private set

    /** Called from [DungeonSession.reset]: these are per run, like the clock they are counted against. */
    fun reset() {
        idleTicks = 0
        navTicks = 0
    }

    /**
     * What the local player was doing this tick, given the room they are standing in ([room] null
     * for no room at all).
     *
     * The `preCleared` clause is spelled out rather than folded into [TrackedRoom.cleared], even
     * though `ContributionTracker.discover` stamps `clearedAtTick` on a pre-cleared room today and
     * therefore makes the two agree. The contract names `preCleared` explicitly, so it is checked
     * explicitly: a later change to when that stamp happens would otherwise take pre-cleared rooms
     * out of `idleTicks` silently, and standing in the entrance is the most ordinary idle there is.
     * `a preCleared room counts even without a clear tick` is the guard.
     */
    internal fun classify(room: TrackedRoom?): Where {
        if (room == null) return Where.NAVIGATING
        if (!room.cleared && !room.preCleared) return Where.WORKING
        if (room.secretRunOpen) return Where.WORKING
        return Where.IDLE
    }

    /** One run tick, spent in [room] (null for none). */
    fun tick(room: TrackedRoom?) {
        when (classify(room)) {
            Where.IDLE -> idleTicks++
            Where.NAVIGATING -> navTicks++
            Where.WORKING -> Unit
        }
    }

    /** The HUD line as it stands. The wiring; [line] with arguments is the contract a test drives. */
    fun line(): String = line(idleTicks, navTicks)

    /**
     * Both numbers, counting up, in the same `m:ss.t` the rest of the readout uses.
     *
     * Never blended into one figure and never expressed as a share of the run: the whole reason
     * there are two is that they call for different fixes, and a percentage of a run that is still
     * in progress moves for reasons that have nothing to do with either.
     */
    internal fun line(idle: Int, nav: Int): String =
        "Idle  ${DungeonGrid.formatTicks(idle)}  ·  Nav  ${DungeonGrid.formatTicks(nav)}"
}
