package sighteaddons.ui.motion

/**
 * Motion durations, and the reduce-motion policy that every animation in this UI passes through.
 *
 * [reduceMotion] is wired here rather than checked at call sites so that it cannot be forgotten by
 * one component: an [Animatable] asks [duration] for its effective length, and a setting that turns
 * motion off turns it off everywhere by construction.
 */
internal object Motion {

    /** Immediate feedback — a press state, a focus ring. */
    const val INSTANT = 80

    /** Hover, small state changes, a caret rotating. */
    const val FAST = 140

    /** The default. Card mount, toggle travel, most transitions. */
    const val BASE = 220

    /** Deliberate, noticed movement — a detail panel sliding in. */
    const val SLOW = 360

    /** Looping. Breathing, shimmer sweeps, skeleton placeholders. */
    const val AMBIENT = 1200

    /**
     * What an animation is doing, which decides what survives [reduceMotion].
     *
     * The rule is the one the spec sets: reduce-motion keeps opacity crossfades of 100 ms or less and
     * nothing else. A user who has asked for less motion has usually asked because movement makes
     * them ill, and a fade does not move.
     */
    enum class Kind {
        /** A crossfade. Survives reduce-motion, clamped. */
        OPACITY,

        /** Anything that travels, scales or rotates. Snaps under reduce-motion. */
        TRANSFORM,

        /** A loop. Stops entirely under reduce-motion, holding at its rest value. */
        AMBIENT,
    }

    /** Set from config. */
    var reduceMotion = false

    /**
     * How long an animation of [kind] should actually take, given the current setting.
     *
     * `0` means "no animation": the caller jumps straight to the target. Callers must handle that
     * rather than dividing by it.
     */
    fun duration(requestedMs: Int, kind: Kind): Int {
        if (!reduceMotion) return requestedMs
        return when (kind) {
            Kind.OPACITY -> if (requestedMs < REDUCED_FADE_MS) requestedMs else REDUCED_FADE_MS
            Kind.TRANSFORM -> 0
            Kind.AMBIENT -> 0
        }
    }

    /** Whether an ambient loop should run at all. */
    fun ambientEnabled(): Boolean = !reduceMotion

    private const val REDUCED_FADE_MS = 100
}
