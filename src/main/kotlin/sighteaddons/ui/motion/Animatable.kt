package sighteaddons.ui.motion

/**
 * One animated scalar. A component declares a target; this interpolates toward it.
 *
 * There is no `update()` and no per-frame tick. The value is a pure function of [Clock.nowMs], the
 * start stamp and the duration, so nothing has to be driven, nothing can be missed if a component
 * skips a frame, and a component that is not drawn costs nothing at all. It is also what keeps this
 * honest across frame rates: at 60 fps and at 240 fps the same wall-clock instant yields the same
 * value, because the frame rate never enters the arithmetic.
 *
 * Not thread-safe, and does not need to be: every instance belongs to one component on the render
 * thread.
 */
internal class Animatable(initial: Float = 0f) {

    private var from = initial
    private var to = initial
    private var startMs = 0.0
    private var durationMs = 0.0
    private var easing: Easing = Easing.STANDARD

    /** Where it is heading. Equals [value] once settled. */
    val target: Float get() = to

    /** The current interpolated value. */
    val value: Float
        get() {
            if (durationMs <= 0.0) return to
            val elapsed = Clock.nowMs - startMs
            if (elapsed <= 0.0) return from
            if (elapsed >= durationMs) return to
            return from + (to - from) * easing.ease((elapsed / durationMs).toFloat())
        }

    /** Whether the value is still moving — useful for deciding an ambient loop can stop. */
    val running: Boolean
        get() = durationMs > 0.0 && Clock.nowMs - startMs < durationMs

    /**
     * Animate toward [value].
     *
     * Re-targeting mid-flight is the normal case, not an edge case — a row hovered and unhovered
     * inside 140 ms must reverse from wherever it currently is, not snap back to the start and slide
     * again. Asking for the target it is already heading to is ignored, so a component may call this
     * unconditionally every frame.
     */
    fun animateTo(
        value: Float,
        ms: Int = Motion.BASE,
        easing: Easing = Easing.STANDARD,
        kind: Motion.Kind = Motion.Kind.TRANSFORM,
    ) {
        if (value == to) return

        val effective = Motion.duration(ms, kind)
        if (effective <= 0) {
            snapTo(value)
            return
        }

        from = this.value
        to = value
        startMs = Clock.nowMs
        durationMs = effective.toDouble()
        this.easing = easing
    }

    /** Jump to [value] with no animation. Used for initial state and for reduce-motion. */
    fun snapTo(value: Float) {
        from = value
        to = value
        startMs = Clock.nowMs
        durationMs = 0.0
    }
}
