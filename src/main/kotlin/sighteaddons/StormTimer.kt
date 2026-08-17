package sighteaddons

import java.util.Locale

/**
 * Storm's cast countdown: two of his lines start a clock, and when it runs out there is a short
 * window in which the shot lands.
 *
 * The second half of the port that brought [CritMeter] in — same `CC0-1.0` source mod, same author,
 * same three exclusions (no network call, no automatic chat, none of its Java). What came across is
 * the behaviour: `[BOSS] Storm: ENERGY HEED MY CALL!` or `[BOSS] Storm: THUNDER LET ME BE YOUR
 * CATALYST!` starts a countdown, `SHOOT NOW` holds for a moment after it expires, and then the
 * readout is gone until he says one of those lines again.
 *
 * ## Two numbers nobody here can derive, which is why both are settings
 *
 * The source mod hard-codes **138 ticks** of countdown and **20 ticks** of `SHOOT NOW` and explains
 * neither, and there is no derivation for either anywhere in this repository. They are defaults, not
 * facts. **Unlike a wrong string a wrong tick count never announces itself** — the readout still
 * counts down, still escalates, still says `SHOOT NOW`, and simply says it at the wrong moment. A
 * player watching it has no way to tell "this is 6.9 s because that is Storm's cast time" from "this
 * is 6.9 s because a stranger's decompiled jar said so".
 *
 * So [Config.stormCountdownTicks] and [Config.stormShootTicks] are both adjustable from `/sa`, in
 * whole ticks, and nothing in this file treats either as authoritative. If the user says the timer
 * fires early or late, those two rows are the entire fix and no code changes.
 *
 * ## The strings are hypotheses, exactly as [CritMeter]'s are
 *
 * They were read out of a decompiled jar. `grep -ril storm` over `docs/evidence/` and the twenty
 * real session logs on disk finds nothing, because no build has ever looked for them — the same
 * answer `critcalc-001` got and for the same reason, so their absence is not evidence against them.
 * [nearMiss] is what turns that into a measurement: any `[BOSS] Storm:` line that is not one of the
 * two triggers is written to the debug log verbatim, so one played M7 either confirms the wording or
 * quotes the real one. See `stormtimer-001`'s `verification_manual` for what the two failure signals
 * look like and what each of them means.
 *
 * ## No ticking, and therefore no state machine
 *
 * The source mod kept five mutable fields and advanced them from a `ClientTickEvents` callback. All
 * of it is a function of one number — how many ticks have passed since the trigger — so this keeps
 * that number and nothing else: [startTick], written once per trigger, and [readout] which is total
 * and pure over the elapsed count. That removes the whole class of bug where the display and the
 * state disagree because a tick was missed, and it means the arithmetic is driven directly by
 * `StormTimerTest` rather than being simulated.
 *
 * It also sidesteps a real hazard. `SighteAddons.onTick` returns early several times over — not in a
 * dungeon, no readable map, not calibrated — and Storm is a boss phase, which is on the far side of
 * two of those returns. A countdown that depended on that callback would be a countdown that could
 * quietly not run in the only place it is ever used.
 *
 * The unverified half is the wiring, as everywhere else here: that Fabric delivers Storm's line to
 * `SighteAddons.onChat` with `overlay` false, that `client.level.gameTime` is advancing while it
 * does, and that `SighteAddons.renderHud` is reached during the boss at all.
 */
internal object StormTimer {
    /**
     * The two lines that start a countdown, start-anchored for the reason every chat pattern in this
     * repository is: Hypixel puts `Name: ` in front of anything a player typed, so a teammate saying
     * these words arrives behind their own name and cannot start your timer. The source mod used
     * `String.equals`, which is anchored at both ends by accident; the trailing `.*` is deliberate,
     * matching [CritMeter]'s reasoning that a token appended in some later season should cost the
     * tail rather than the reading.
     */
    private val TRIGGER =
        Regex("""^\[BOSS] Storm: (?:ENERGY HEED MY CALL!|THUNDER LET ME BE YOUR CATALYST!).*$""")

    /** Anything Storm says. Only used by [nearMiss] — see there for why it is safe to log. */
    private val STORM = Regex("""^\[BOSS] Storm:.*$""")

    /**
     * What the countdown can be set to, in ticks, and the range `/sa` steps through.
     *
     * Wide on purpose. The default is inherited and unverified, so a clamp tight enough to look
     * confident would be a clamp that cannot express the correction. The lower bound is one tick —
     * below that there is no countdown to show — and the upper is twenty seconds, past which the
     * readout would be on screen for most of the phase.
     */
    const val COUNTDOWN_MIN = 1
    const val COUNTDOWN_MAX = 400

    /** The same argument for the `SHOOT NOW` hold: at least one tick, at most five seconds. */
    const val SHOOT_MIN = 1
    const val SHOOT_MAX = 100

    /** Three seconds and one second, in ticks — where [Urgency] steps up. */
    private const val CALM_ABOVE = 60
    private const val CLOSING_ABOVE = 20

    /**
     * `client.level.gameTime` when Storm last spoke, or null for no countdown running.
     *
     * `@Volatile` for the same reason [IdleTime.idleTicks] is, one direction reversed: this is
     * written from the chat path and read by [StormHud] on the render thread, every frame. A `Long`
     * is not written atomically without it, so a reader could see half of one — and the halves of two
     * different world times make an elapsed count that is off by billions rather than off by one.
     * Whether Fabric delivers chat on the client thread is asserted elsewhere in this repository and
     * has never been measured; the volatile costs nothing and does not depend on that being true.
     */
    @Volatile
    var startTick: Long? = null
        private set

    /**
     * Called from [DungeonSession.reset]: a countdown belongs to the fight it started in.
     *
     * Left standing it would be worse than stale. World time does not reset with the run, so a
     * countdown carried into the next floor is one whose elapsed count is already enormous — it
     * would read as expired rather than as running, which is quiet, but the same reasoning that shuts
     * [CritMeter]'s window applies and a run should not inherit any of the previous one's state.
     */
    fun reset() {
        startTick = null
    }

    /**
     * One chat line. Returns true when it started a countdown, which is what the caller logs.
     *
     * [worldTime] is a lambda for the same reason [CritMeter.onChat] takes the tab footer as one: it
     * is the only part that needs a live `Minecraft`, so passing it in is what lets the decision run
     * end to end in a test. It is called only after the line has matched.
     *
     * A null world time — no level loaded — drops the trigger rather than starting a countdown from
     * an invented origin. That is the source mod's behaviour too, and it is the right one: a clock
     * with no start is indistinguishable on screen from one that is right.
     */
    fun onChat(stripped: String, worldTime: () -> Long?): Boolean {
        if (TRIGGER.matchEntire(CritMeter.normalize(stripped)) == null) return false
        val now = worldTime() ?: return false
        startTick = now
        return true
    }

    /**
     * A line Storm said that is not one of the two triggers, or null. Written to the debug log by the
     * caller as `storm_unparsed`.
     *
     * **This is the only thing that will ever confirm or correct the two strings above.** Safe to log
     * verbatim for the same reason [CritMeter.nearMiss] is: `[BOSS] ` is a prefix only the server
     * produces, so this cannot capture a player's conversation, and an NPC line carries no name to
     * redact.
     *
     * Every other Storm line is reported, not just ones that look like a trigger. The wording of the
     * two that matter is exactly what is in doubt, so a filter narrow enough to only catch near-misses
     * would be a filter built out of the assumption being tested.
     */
    fun nearMiss(stripped: String): String? {
        val line = CritMeter.normalize(stripped)
        if (STORM.matchEntire(line) == null) return null
        return if (TRIGGER.matchEntire(line) == null) line else null
    }

    /**
     * How close the shot is, in the four steps the source mod drew as four colours.
     *
     * A step and not an ARGB value, because this file has no business owning one. The source mod
     * returned blue/yellow/red/bright-red; this UI is monochrome by design and has no hue to spend,
     * so *how* four steps are told apart is a question only the presentation layer can answer — and
     * the answer it gives ([StormHud]) is a count of filled marks and a border weight, not four
     * greys. Had the timer kept returning colours, the only monochrome answer available to the
     * drawing half would have been luminance alone, which this design explicitly forbids.
     *
     * Four steps and not three: `SHOOT NOW` is a different statement from "the last second", and the
     * source mod already separated them.
     */
    internal enum class Urgency {
        /** More than three seconds out. */
        CALM,

        /** Inside three seconds. */
        CLOSING,

        /** The last second of the countdown. */
        IMMINENT,

        /** The window itself. */
        NOW,
    }

    /** What is on screen: the text and how hard it should land. Null from [readout] draws nothing. */
    internal data class Readout(val text: String, val urgency: Urgency)

    /**
     * The whole display decision, as a function of the ticks since the trigger.
     *
     * Null in three cases, which are one case on screen and three different facts underneath:
     * no countdown has been started; [elapsed] is negative, which is a world time that went backwards
     * under a running countdown and means the level was swapped; and the countdown plus its hold have
     * both run out, which is where the source mod called `stop()`.
     *
     * The seconds shown are the countdown's remaining ticks over twenty, so the last frame before
     * `SHOOT NOW` reads `0.1s` and never `0.0s` — at zero remaining the state has already changed.
     * The [Urgency] boundaries are the source mod's three seconds and one second, expressed in ticks
     * so they land exactly rather than on whichever side of `3.0f` a float division puts them.
     */
    internal fun readout(elapsed: Long?, countdown: Int, shoot: Int): Readout? {
        if (elapsed == null || elapsed < 0L) return null
        if (elapsed >= countdown.toLong() + shoot.toLong()) return null
        if (elapsed >= countdown.toLong()) return Readout("SHOOT NOW", Urgency.NOW)
        val remaining = countdown - elapsed
        val urgency = when {
            remaining > CALM_ABOVE -> Urgency.CALM
            remaining > CLOSING_ABOVE -> Urgency.CLOSING
            else -> Urgency.IMMINENT
        }
        // Locale.ROOT for the reason every number this mod prints uses it: a German default locale
        // renders "6,9" and the readout would stop matching everything around it.
        return Readout("Storm  %.1fs".format(Locale.ROOT, remaining / 20.0), urgency)
    }

    /** The readout as it stands, for [worldTime]. The wiring; [readout] is the contract a test drives. */
    fun readoutAt(worldTime: Long?): Readout? {
        val start = startTick ?: return null
        if (worldTime == null) return null
        return readout(worldTime - start, Config.stormCountdownTicks, Config.stormShootTicks)
    }

    /**
     * One click on a `/sa` tick row: [value] plus one, wrapping back to [min] past [max], or the
     * other way with shift held.
     *
     * A step and not a text field because this screen has no text input outside the history search,
     * and a wrap and not a clamp because a value parked at either end with no way back would be a
     * setting the player can break by clicking. One tick per click is deliberate: the plausible
     * correction to an inherited timing is a handful of ticks, and a coarser step could not express
     * it at all.
     */
    internal fun step(value: Int, min: Int, max: Int, back: Boolean): Int {
        val next = if (back) value - 1 else value + 1
        return when {
            next > max -> min
            next < min -> max
            else -> next
        }
    }

    /** How a tick count is written in `/sa`: the number that is stored, and what it means. */
    internal fun ticksLabel(ticks: Int): String = "%d ticks · %.2fs".format(Locale.ROOT, ticks, ticks / 20.0)
}
