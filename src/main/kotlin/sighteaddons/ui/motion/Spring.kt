package sighteaddons.ui.motion

/**
 * A spring, for the two places a Bézier reads as too mechanical: a toggle knob and a slider grab.
 *
 * Solved analytically rather than integrated per frame. A numeric spring has to be stepped every
 * frame with the frame's delta, which makes its result depend on the frame rate — exactly what this
 * UI is not allowed to do — and needs a velocity carried between frames. The closed form for an
 * underdamped second-order step response has neither problem: it is a pure function of elapsed time,
 * so it matches [Animatable]'s shape and is identical at 60 and 240 fps.
 *
 * ```
 * x(t) = 1 − e^(−ζωt) · ( cos(ω_d t) + (ζω / ω_d) · sin(ω_d t) ),   ω_d = ω√(1 − ζ²)
 * ```
 *
 * [DAMPING] is 0.667, which puts the first peak at 1.06 — the slight overshoot the spec asks for.
 * Overshoot for an underdamped system is `exp(−πζ / √(1 − ζ²))`, so that value is derived, not
 * guessed: 0.667 gives 6.0 %.
 */
internal class Spring(initial: Float = 0f) {

    private var from = initial
    private var to = initial
    private var startMs = 0.0
    private var durationMs = 0.0

    val target: Float get() = to

    /**
     * The current value. Overshoots [target] by up to 6 % around 56 % of the way through, then
     * settles.
     */
    val value: Float
        get() {
            if (durationMs <= 0.0) return to
            val elapsed = Clock.nowMs - startMs
            if (elapsed <= 0.0) return from
            if (elapsed >= durationMs) return to
            return from + (to - from) * response((elapsed / durationMs).toFloat())
        }

    val running: Boolean
        get() = durationMs > 0.0 && Clock.nowMs - startMs < durationMs

    /**
     * Spring toward [value].
     *
     * Always [Motion.Kind.TRANSFORM]: a spring is movement by definition, so under reduce-motion it
     * snaps. There is no meaningful "reduced" spring — a spring with the bounce removed is a Bézier,
     * and one that is merely faster still moves.
     */
    fun springTo(value: Float, ms: Int = Motion.BASE) {
        if (value == to) return

        val effective = Motion.duration(ms, Motion.Kind.TRANSFORM)
        if (effective <= 0) {
            snapTo(value)
            return
        }

        from = this.value
        to = value
        startMs = Clock.nowMs
        durationMs = effective.toDouble()
    }

    fun snapTo(value: Float) {
        from = value
        to = value
        startMs = Clock.nowMs
        durationMs = 0.0
    }

    companion object {

        /** ζ. Yields a 6 % first peak; see the class comment for the derivation. */
        const val DAMPING = 0.667f

        /**
         * ζω. Fixed at 5 so that by the nominal duration the residual is `e^-5` ≈ 0.7 %, i.e. the
         * spring has visually arrived exactly when its stated duration says it has.
         */
        private const val DECAY = 5.0

        private val OMEGA = DECAY / DAMPING
        private val OMEGA_D = OMEGA * Math.sqrt(1.0 - DAMPING.toDouble() * DAMPING)
        private val RATIO = DECAY / OMEGA_D

        /** Normalised step response at [t] in `0f..1f`. */
        fun response(t: Float): Float {
            val time = t.toDouble()
            val envelope = Math.exp(-DECAY * time)
            val phase = OMEGA_D * time
            return (1.0 - envelope * (Math.cos(phase) + RATIO * Math.sin(phase))).toFloat()
        }
    }
}
